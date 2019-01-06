package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

internal val DeclarationItem.it get() = declaration as RealDeclaration
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

data class Edge(val from: CFGNode, val to: CFGNode)

sealed class CFGTerminator : CFGNode()

class CondJump(val cond: Expression?,
               var target: BasicBlock,
               var other: BasicBlock) : CFGTerminator()

class UncondJump(val target: BasicBlock) : CFGTerminator()

class Return(val value: Expression?) : CFGTerminator()

class BasicBlock(vararg initPreds: BasicBlock, term: CFGTerminator? = null) : CFGNode() {
  val preds: MutableList<BasicBlock> = initPreds.toMutableList()
  val data: MutableList<ASTNode> = mutableListOf()
  var terminator: CFGTerminator? = term
    private set

  init {
    preds.filter { it.data.isEmpty() }.forEach inner@{ emptyBlock ->
      emptyBlock.preds.forEach {
        val oldTerm = it.terminator!!
        when (oldTerm) {
          is UncondJump -> it.terminator = UncondJump(this)
          is CondJump -> {
            oldTerm.target = if (oldTerm.target == emptyBlock) this else oldTerm.target
            oldTerm.other = if (oldTerm.other == emptyBlock) this else oldTerm.other
          }
          else -> return@inner
        }
        this.preds += it
      }
    }
  }

  fun setTerminator(lazyTerminator: () -> CFGTerminator) {
    if (terminator != null) return
    terminator = lazyTerminator()
  }

  fun graphDataOf(): Pair<List<CFGNode>, List<Edge>> {
    val nodes = mutableListOf<CFGNode>()
    val edges = mutableListOf<Edge>()
    graphDataImpl(nodes, edges)
    return Pair(nodes, edges.distinct())
  }

  /** Implementation detail of [graphDataOf]. Recursive case. */
  private fun graphDataImpl(nodes: MutableList<CFGNode>, edges: MutableList<Edge>) {
    // The graph can be cyclical, and we don't want to enter an infinite loop
    if (nodes.contains(this)) return
    nodes.add(this)
    if (terminator == null) return
    nodes.add(terminator!!)
    when (terminator) {
      is UncondJump -> {
        val target = (terminator!! as UncondJump).target
        edges.add(Edge(this, target))
        target.graphDataImpl(nodes, edges)
      }
      is CondJump -> {
        edges.add(Edge(this, terminator!!))
        val t = terminator as CondJump
        edges.add(Edge(t, t.target))
        t.target.graphDataImpl(nodes, edges)
        edges.add(Edge(t, t.other))
        t.other.graphDataImpl(nodes, edges)
      }
      is Return -> {
        edges.add(Edge(this, terminator!!))
      }
    }
  }
}

/**
 * Pretty graph for debugging purposes.
 * Recommended usage:
 * ```
 * ckompiler --print-cfg-graphviz /tmp/file.c 2> /dev/null |
 * dot -Tpng > /tmp/CFG.png && xdg-open /tmp/CFG.png
 * ```
 */
fun createGraphviz(graphRoot: BasicBlock, sourceCode: String): String {
  val (nodes, edges) = graphRoot.graphDataOf()
  val sep = "\n  "
  val content = nodes.joinToString(sep) {
    when (it) {
      is BasicBlock -> {
        val code = it.data.joinToString("\n") { node -> node.originalCode(sourceCode) }
        "${it.id} [shape=box,label=\"${if (code.isBlank()) "<EMPTY>" else code}\"];"
      }
      is UncondJump -> "// unconditional jump ${it.id}"
      is CondJump -> "${it.id} [shape=diamond,label=\"${it.cond?.originalCode(sourceCode)}\"];"
      is Return -> "${it.id} [shape=ellipse,label=\"${it.value?.originalCode(sourceCode)}\"];"
    }
  } + sep + edges.joinToString(sep) { "${it.from.id} -> ${it.to.id};" }
  return "digraph CFG {$sep$content\n}"
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
    val afterLoopBlock = BasicBlock(current, loopNext)
    current.setTerminator { UncondJump(loopBlock) }
    loopNext.setTerminator { CondJump(s.cond, loopBlock, afterLoopBlock) }
    afterLoopBlock
  }
  is ForStatement -> TODO()
  is ContinueStatement -> TODO()
  is BreakStatement -> TODO()
  is GotoStatement -> TODO()
  is ReturnStatement -> {
    current.setTerminator { Return(s.expr) }
    // FIXME: create a new block to contain all the stuff after the return
    current
  }
}
