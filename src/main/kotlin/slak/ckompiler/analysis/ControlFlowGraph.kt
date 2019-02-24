package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("ControlFlow")

sealed class Jump

/** If [cond] is true, jump to [target], otherwise jump to [other]. */
data class CondJump(val cond: Expression, val target: BasicBlock, val other: BasicBlock) : Jump()

/** Unconditionally jump to [target]. */
data class UncondJump(val target: BasicBlock) : Jump()

/** A so-called "impossible edge" of the CFG. Like a [UncondJump], but will never be traversed. */
data class ImpossibleJump(val target: BasicBlock) : Jump()

/**
 * Combination of [UncondJump] and [ImpossibleJump].
 * Always jumps to [target], never to [impossible].
 */
data class ConstantJump(val target: BasicBlock, val impossible: BasicBlock) : Jump()

/** Indicates an incomplete [BasicBlock]. */
object MissingJump : Jump()

private data class GraphContext(val currentLoopBlock: BasicBlock? = null,
                                val loopAfterBlock: BasicBlock? = null,
                                val labels: List<Pair<String, BasicBlock>> = emptyList()) {
  fun labelBlockFor(labelName: String): BasicBlock {
    val blockOrNull = labels.firstOrNull { it.first == labelName }?.second
    blockOrNull ?: logger.throwICE("Can't find label block in context") { "$this/$labelName" }
    return blockOrNull
  }
}

class BasicBlock(val isRoot: Boolean = false) {
  private val preds: MutableSet<BasicBlock> = mutableSetOf()
  val data: MutableList<ASTNode> = mutableListOf()
  var terminator: Jump = MissingJump
    private set(value) {
      field = value
      when (value) {
        is CondJump -> {
          value.target.preds += this
          value.other.preds += this
        }
        is ConstantJump -> {
          value.target.preds += this
          value.impossible.preds += this
        }
        is UncondJump -> value.target.preds += this
        is ImpossibleJump -> value.target.preds += this
      }
    }

  override fun toString() = "BasicBlock(${data.joinToString("\n")})"

  fun isTerminated() = terminator !is MissingJump

  /**
   * Collapses empty predecessor blocks to this one if possible, and does so recursively up the
   * graph. Run on the exit block to collapse everything in the graph (the exit block should
   * post-dominate all other blocks).
   */
  fun collapseIfEmptyRecusively() {
    collapseImpl(mutableListOf())
  }

  private fun collapseImpl(nodes: MutableList<BasicBlock>) {
    if (this in nodes) return
    nodes += this
    emptyBlockLoop@ for (emptyBlock in preds.filter { it.data.isEmpty() }) {
      if (emptyBlock.terminator !is UncondJump) continue
      for (emptyBlockPred in emptyBlock.preds) {
        val oldTerm = emptyBlockPred.terminator
        when (oldTerm) {
          is UncondJump -> emptyBlockPred.terminator = UncondJump(this)
          is ImpossibleJump -> emptyBlockPred.terminator = ImpossibleJump(this)
          is CondJump -> {
            emptyBlockPred.terminator = CondJump(
                oldTerm.cond,
                if (oldTerm.target == emptyBlock) this else oldTerm.target,
                if (oldTerm.other == emptyBlock) this else oldTerm.other
            )
          }
          is ConstantJump -> {
            emptyBlockPred.terminator = ConstantJump(
                if (oldTerm.target == emptyBlock) this else oldTerm.target,
                if (oldTerm.impossible == emptyBlock) this else oldTerm.impossible
            )
          }
          else -> continue@emptyBlockLoop
        }
        this.preds += emptyBlockPred
      }
      emptyBlock.preds.clear()
      this.preds -= emptyBlock
    }
    preds.forEach { it.collapseImpl(nodes) }
  }

  companion object {
    fun createGraphFor(f: FunctionDefinition): Pair<BasicBlock, BasicBlock> {
      val startBlock = BasicBlock(isRoot = true)
      val labels = f.block.scope.labels.map { it.name to BasicBlock() }
      val exitBlock = GraphContext(labels = labels).graphCompound(startBlock, f.block)
      return startBlock to exitBlock
    }

    private fun GraphContext.graphCompound(current: BasicBlock,
                                           compoundStatement: CompoundStatement): BasicBlock {
      var block = current
      val newLabels = compoundStatement.scope.labels.map { it.name to BasicBlock() }
      val context = this.copy(labels = labels + newLabels)
      for (item in compoundStatement.items) {
        when (item) {
          is StatementItem -> block = context.graphStatement(block, item.statement)
          is DeclarationItem -> block.data += item.declaration
        }
      }
      return block
    }

    private fun GraphContext.graphStatement(current: BasicBlock,
                                            s: Statement): BasicBlock = when (s) {
      is ErrorStatement,
      is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is Expression, is Noop -> {
        current.data += s
        current
      }
      is LabeledStatement -> {
        val blockWithLabel = labelBlockFor(s.label.name)
        blockWithLabel.data += s
        current.terminator = UncondJump(blockWithLabel)
        blockWithLabel
      }
      is CompoundStatement -> graphCompound(current, s)
      is IfStatement -> {
        val ifBlock = BasicBlock()
        val elseBlock = BasicBlock()
        val ifNext = graphStatement(ifBlock, s.success)
        val elseNext = s.failure?.let { graphStatement(elseBlock, it) }
        val afterIfBlock = BasicBlock()
        current.terminator = run {
          val falseBlock = if (elseNext != null) elseBlock else afterIfBlock
          CondJump(s.cond, ifBlock, falseBlock)
        }
        ifNext.terminator = UncondJump(afterIfBlock)
        elseNext?.terminator = UncondJump(afterIfBlock)
        afterIfBlock
      }
      is SwitchStatement -> TODO("implement switches")
      is WhileStatement -> {
        val loopBlock = BasicBlock()
        val afterLoopBlock = BasicBlock()
        val loopContext = GraphContext(loopBlock, afterLoopBlock, labels)
        val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
        current.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
        loopNext.terminator = UncondJump(current)
        afterLoopBlock
      }
      is DoWhileStatement -> {
        val loopBlock = BasicBlock()
        val afterLoopBlock = BasicBlock()
        val loopContext = GraphContext(loopBlock, afterLoopBlock, labels)
        val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
        current.terminator = UncondJump(loopBlock)
        loopNext.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
        afterLoopBlock
      }
      is ForStatement -> {
        when (s.init) {
          is EmptyInitializer -> { /* Intentionally left empty */ }
          is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
          is ForExpressionInitializer -> current.data += s.init.value
          is DeclarationInitializer -> current.data += s.init.value
        }
        val loopBlock = BasicBlock()
        val afterLoopBlock = BasicBlock()
        val loopContext = GraphContext(loopBlock, afterLoopBlock, labels)
        val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
        s.loopEnd?.let { graphStatement(loopNext, it) }
        if (s.cond == null) {
          // No for condition means unconditional jump to loop block
          current.terminator = UncondJump(loopBlock)
        } else {
          current.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
        }
        loopNext.terminator = current.terminator
        afterLoopBlock
      }
      is ContinueStatement -> {
        val afterContinue = BasicBlock()
        current.terminator = ConstantJump(currentLoopBlock!!, afterContinue)
        afterContinue
      }
      is BreakStatement -> {
        val afterBreak = BasicBlock()
        current.terminator = ConstantJump(loopAfterBlock!!, afterBreak)
        afterBreak
      }
      is GotoStatement -> {
        val labelBlock = labelBlockFor(s.identifier.name)
        val afterGoto = BasicBlock()
        current.terminator = ConstantJump(labelBlock, afterGoto)
        afterGoto
      }
      is ReturnStatement -> {
        current.data += s
        val deadCodeBlock = BasicBlock()
        current.terminator = ImpossibleJump(deadCodeBlock)
        deadCodeBlock
      }
    }
  }
}
