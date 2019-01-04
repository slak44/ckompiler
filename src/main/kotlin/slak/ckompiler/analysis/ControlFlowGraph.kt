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
  abstract val id: String
}

data class Edge(val from: CFGNode, val to: CFGNode)

data class Jump(val cond: Expression?, val target: BasicBlock, val other: BasicBlock) : CFGNode() {
  override val id: String get() = "jump_${hashCode()}"
}

data class BasicBlock(val data: MutableList<ASTNode>,
                      val preds: List<BasicBlock>,
                      var jmp: Jump?) : CFGNode() {
  override val id: String get() = "block_${hashCode()}"

  fun graphDataOf(): Pair<List<CFGNode>, List<Edge>> {
    val nodes = mutableListOf<CFGNode>()
    val edges = mutableListOf<Edge>()
    graphDataImpl(nodes, edges)
    return Pair(nodes, edges)
  }

  private fun graphDataImpl(nodes: MutableList<CFGNode>, edges: MutableList<Edge>) {
    nodes.add(this)
    if (jmp == null) return
    nodes.add(jmp!!)
    edges.add(Edge(this, jmp!!))
    edges.add(Edge(jmp!!, jmp!!.target))
    if (jmp!!.target != jmp!!.other) {
      edges.add(Edge(jmp!!, jmp!!.other))
      jmp!!.target.graphDataImpl(nodes, edges)
      jmp!!.other.graphDataImpl(nodes, edges)
    } else {
      jmp!!.target.graphDataImpl(nodes, edges)
    }
  }
}

fun createGraphviz(graphRoot: BasicBlock): String {
  val (nodes, edges) = graphRoot.graphDataOf()
  val content = nodes.joinToString("\n") {
    when (it) {
      is BasicBlock -> "${it.id} [shape=box,label=${it.data.joinToString()}];"
      is Jump -> "${it.id} [shape=diamond,label=${it.cond.toString()}];"
    }
  } + "\n" + edges.joinToString("\n") { "${it.from.id} -> ${it.to.id};" }
  return "digraph CFG {\n$content\n}"
}

fun createGraphFor(f: FunctionDefinition) {
  val init = BasicBlock(mutableListOf(), emptyList(), null)
  graphCompound(init, f.block)
}

fun graphCompound(current: BasicBlock, compoundStatement: CompoundStatement): BasicBlock {
  var block = current
  for (item in compoundStatement.items) {
    when (item) {
      is StatementItem -> block = graphStatement(block, item.statement)
      is DeclarationItem -> {
        TODO("implement")
      }
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
    current.jmp = Jump(s.cond, ifBlock, elseBlock)
    graphStatement(ifBlock, s.success)
    s.failure?.let { graphStatement(elseBlock, it) }
    if (ifBlock.jmp == null) ifBlock.jmp = Jump(null, afterIfBlock, afterIfBlock)
    if (elseBlock.jmp == null) elseBlock.jmp = Jump(null, afterIfBlock, afterIfBlock)
    afterIfBlock
  }
  is SwitchStatement -> TODO("implement switches")
  is WhileStatement -> TODO()
  is DoWhileStatement -> TODO()
  is ForStatement -> TODO()
  is ContinueStatement -> TODO()
  is BreakStatement -> TODO()
  is GotoStatement -> TODO()
  is ReturnStatement -> TODO()
}
