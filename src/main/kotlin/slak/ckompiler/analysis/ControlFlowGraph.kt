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

  companion object {
    private var objIndex = 0
  }
}

data class Edge(val from: CFGNode, val to: CFGNode)

sealed class CFGTerminator : CFGNode()

data class Jump(val cond: Expression?,
                val target: BasicBlock,
                val other: BasicBlock) : CFGTerminator()

data class Return(val value: Expression?) : CFGTerminator()

data class BasicBlock(val data: MutableList<ASTNode>,
                      val preds: List<BasicBlock>,
                      var term: CFGTerminator?) : CFGNode() {

  fun graphDataOf(): Pair<List<CFGNode>, List<Edge>> {
    val nodes = mutableListOf<CFGNode>()
    val edges = mutableListOf<Edge>()
    graphDataImpl(nodes, edges)
    return Pair(nodes, edges)
  }

  private fun graphDataImpl(nodes: MutableList<CFGNode>, edges: MutableList<Edge>) {
    nodes.add(this)
    if (term == null) return
    nodes.add(term!!)
    edges.add(Edge(this, term!!))
    if (term !is Jump) return
    val t = term as Jump
    edges.add(Edge(t, t.target))
    if (t.target != t.other) {
      edges.add(Edge(t, t.other))
      t.target.graphDataImpl(nodes, edges)
      t.other.graphDataImpl(nodes, edges)
    } else {
      t.target.graphDataImpl(nodes, edges)
    }
  }
}

fun createGraphviz(graphRoot: BasicBlock): String {
  val (nodes, edges) = graphRoot.graphDataOf()
  val content = nodes.joinToString("\n") {
    when (it) {
      is BasicBlock -> "${it.id} [shape=box,label=\"${it.data.joinToString("\n")}\"];"
      is Jump -> "${it.id} [shape=diamond,label=\"${it.cond.toString()}\"];"
      is Return -> "${it.id} [shape=ellipse,label=\"${it.value.toString()}\"];"
    }
  } + "\n" + edges.joinToString("\n") { "${it.from.id} -> ${it.to.id};" }
  return "digraph CFG {\n$content\n}"
}

fun createGraphFor(f: FunctionDefinition): BasicBlock {
  val init = BasicBlock(mutableListOf(), emptyList(), null)
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
    val ifBlock = BasicBlock(mutableListOf(), listOf(current), null)
    val elseBlock = BasicBlock(mutableListOf(), listOf(current), null)
    val afterIfBlock = BasicBlock(mutableListOf(),
        listOfNotNull(ifBlock, if (s.failure == null) null else elseBlock), null)
    current.term = Jump(s.cond, ifBlock, elseBlock)
    graphStatement(ifBlock, s.success)
    s.failure?.let { graphStatement(elseBlock, it) }
    if (ifBlock.term == null) ifBlock.term = Jump(null, afterIfBlock, afterIfBlock)
    if (elseBlock.term == null) elseBlock.term = Jump(null, afterIfBlock, afterIfBlock)
    afterIfBlock
  }
  is SwitchStatement -> TODO("implement switches")
  is WhileStatement -> TODO()
  is DoWhileStatement -> TODO()
  is ForStatement -> TODO()
  is ContinueStatement -> TODO()
  is BreakStatement -> TODO()
  is GotoStatement -> TODO()
  is ReturnStatement -> {
    current.term = Return(s.expr)
    // FIXME: create a new block to contain all the stuff after the return
    current
  }
}
