package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

internal val Declarator.name get() = name()!!.name
internal val FunctionDefinition.name get() = functionDeclarator.name
internal val FunctionDefinition.block get() = compoundStatement as CompoundStatement

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

class CondJump(val cond: Expression?,
               var target: BasicBlock,
               var other: BasicBlock) : CFGTerminator()

class UncondJump(val target: BasicBlock) : CFGTerminator()

class Return(val value: Expression?) : CFGTerminator()

class BasicBlock(vararg initPreds: BasicBlock, term: CFGTerminator? = null) : CFGNode() {
  private val preds: MutableList<BasicBlock> = initPreds.toMutableList()
  val data: MutableList<ASTNode> = mutableListOf()
  var terminator: CFGTerminator? = term
    private set
  private var isDead = false

  override fun toString() =
      if (isDead) "DEAD@${hashCode()}" else "BasicBlock(${data.joinToString("\n")})"

  fun isStart() = preds.isEmpty()

  private fun collapseEmptyBlocks() {
    preds.filter { it.data.isEmpty() }.forEach emptyBlockLoop@{ emptyBlock ->
      if (emptyBlock.terminator is CondJump) return@emptyBlockLoop
      emptyBlock.preds.forEach {
        val oldTerm = it.terminator!!
        when (oldTerm) {
          is UncondJump -> it.terminator = UncondJump(this)
          is CondJump -> {
            oldTerm.target = if (oldTerm.target == emptyBlock) this else oldTerm.target
            oldTerm.other = if (oldTerm.other == emptyBlock) this else oldTerm.other
          }
          else -> return@emptyBlockLoop
        }
        this.preds += it
      }
      this.preds -= emptyBlock
      emptyBlock.isDead = true
    }
  }

  fun setTerminator(lazyTerminator: () -> CFGTerminator) {
    if (terminator != null) return
    terminator = lazyTerminator()
    collapseEmptyBlocks()
  }
}

fun createGraphFor(f: FunctionDefinition): BasicBlock {
  val init = BasicBlock()
  graphCompound(init, f.block)
  return init
}

fun graphCompound(current: BasicBlock, compoundStatement: CompoundStatement): BasicBlock {
  var block = current
  for (item in compoundStatement.items) {
    when (item) {
      is StatementItem -> block = graphStatement(block, item.statement)
      is DeclarationItem -> block.data.add(item.declaration)
    }
  }
  return block
}

fun graphStatement(current: BasicBlock, s: Statement): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression, is LabeledStatement, is Noop -> {
    current.data.add(s)
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
    loopNext.setTerminator { current.terminator!! }
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
      is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is ExpressionInitializer -> current.data.add(s.init.value)
      is DeclarationInitializer -> current.data.add(s.init.value)
    }
    val loopBlock = BasicBlock(current)
    val loopNext = graphStatement(loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(loopNext, it) }
    val afterLoopBlock = BasicBlock(current, loopNext)
    current.setTerminator { CondJump(s.cond, loopBlock, afterLoopBlock) }
    loopNext.setTerminator { current.terminator!! }
    afterLoopBlock
  }
  is ContinueStatement -> TODO()
  is BreakStatement -> TODO()
  is GotoStatement -> TODO()
  is ReturnStatement -> {
    current.setTerminator { Return(s.expr) }
    // FIXME: create a new block to contain all the stuff after the return
    current
  }
}
