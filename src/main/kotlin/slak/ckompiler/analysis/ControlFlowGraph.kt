package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("ControlFlow")

sealed class CFGNode {
  val id: String get() = "${javaClass.simpleName}_$objNr"
  private val objNr = objIndex++

  override fun equals(other: Any?) = objNr == (other as? CFGNode)?.objNr
  override fun hashCode() = objNr

  companion object {
    private var objIndex = 0
  }
}

sealed class CFGTerminator : CFGNode()

data class CondJump(val cond: Expression?,
                    val target: BasicBlock,
                    val other: BasicBlock) : CFGTerminator()

data class UncondJump(val target: BasicBlock) : CFGTerminator()

data class Return(val value: Expression?, val deadCode: BasicBlock? = null) : CFGTerminator()

object Unterminated : CFGTerminator()

class BasicBlock(vararg initPreds: BasicBlock, term: CFGTerminator = Unterminated) : CFGNode() {
  private var isDead = false
  private val preds: MutableSet<BasicBlock> = initPreds.toMutableSet()
  val data: MutableList<ASTNode> = mutableListOf()
  var terminator: CFGTerminator = term
    private set

  override fun toString() =
      if (isDead) "DEAD@${hashCode()}" else "BasicBlock(${data.joinToString("\n")})"

  fun isStart() = preds.isEmpty()

  fun isTerminated() = terminator !is Unterminated

  fun setTerminator(lazyTerminator: () -> CFGTerminator) {
    if (isTerminated()) return
    terminator = lazyTerminator()
    collapseEmptyBlocks()
  }

  private fun collapseEmptyBlocks() {
    preds.filter { it.data.isEmpty() }.forEach emptyBlockLoop@{ emptyBlock ->
      if (emptyBlock.terminator !is UncondJump) return@emptyBlockLoop
      emptyBlock.preds.forEach {
        when (it.terminator) {
          is UncondJump -> it.terminator = UncondJump(this)
          is CondJump -> {
            val t = it.terminator as CondJump
            it.terminator = CondJump(
                t.cond,
                if (t.target == emptyBlock) this else t.target,
                if (t.other == emptyBlock) this else t.other
            )
          }
          else -> return@emptyBlockLoop
        }
        this.preds += it
      }
      this.preds -= emptyBlock
      emptyBlock.isDead = true
    }
  }
}

fun createGraphFor(f: FunctionDefinition): BasicBlock {
  val startBlock = BasicBlock()
  graphCompound(startBlock, f.block)
  return startBlock
}

fun graphCompound(current: BasicBlock, compoundStatement: CompoundStatement): BasicBlock {
  var block = current
  for (item in compoundStatement.items) {
    when (item) {
      is StatementItem -> block = graphStatement(block, item.statement)
      is DeclarationItem -> block.data += item.declaration
    }
  }
  return block
}

fun graphStatement(current: BasicBlock, s: Statement): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression, is LabeledStatement, is Noop -> {
    current.data += s
    current
  }
  is CompoundStatement -> graphCompound(current, s)
  is IfStatement -> {
    val ifBlock = BasicBlock(current)
    val elseBlock = BasicBlock(current)
    val ifNext = graphStatement(ifBlock, s.success)
    val elseNext = s.failure?.let { graphStatement(elseBlock, it) }
    val afterIfBlock = BasicBlock(ifNext, elseNext ?: current)
    current.setTerminator {
      val falseBlock = if (elseNext != null) elseBlock else afterIfBlock
      CondJump(s.cond, ifBlock, falseBlock)
    }
    ifNext.setTerminator { UncondJump(afterIfBlock) }
    elseNext?.setTerminator { UncondJump(afterIfBlock) }
    afterIfBlock
  }
  is SwitchStatement -> TODO("implement switches")
  is WhileStatement -> {
    val loopBlock = BasicBlock(current)
    val loopNext = graphStatement(loopBlock, s.loopable)
    val afterLoopBlock = BasicBlock(current, loopNext)
    current.setTerminator { CondJump(s.cond, loopBlock, afterLoopBlock) }
    loopNext.setTerminator { current.terminator }
    afterLoopBlock
  }
  is DoWhileStatement -> {
    val loopBlock = BasicBlock(current)
    val loopNext = graphStatement(loopBlock, s.loopable)
    val afterLoopBlock = BasicBlock(loopNext)
    current.setTerminator { UncondJump(loopBlock) }
    loopNext.setTerminator { CondJump(s.cond, loopBlock, afterLoopBlock) }
    afterLoopBlock
  }
  is ForStatement -> {
    when (s.init) {
      is EmptyInitializer -> { /* Intentionally left empty */ }
      is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is ForExpressionInitializer -> current.data += s.init.value
      is DeclarationInitializer -> current.data += s.init.value
    }
    val loopBlock = BasicBlock(current)
    val loopNext = graphStatement(loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(loopNext, it) }
    val afterLoopBlock = BasicBlock(current, loopNext)
    current.setTerminator { CondJump(s.cond, loopBlock, afterLoopBlock) }
    loopNext.setTerminator { current.terminator }
    afterLoopBlock
  }
  is ContinueStatement -> TODO()
  is BreakStatement -> TODO()
  is GotoStatement -> TODO()
  is ReturnStatement -> {
    val deadCodeBlock = BasicBlock(current)
    current.setTerminator { Return(s.expr, deadCodeBlock) }
    deadCodeBlock
  }
}
