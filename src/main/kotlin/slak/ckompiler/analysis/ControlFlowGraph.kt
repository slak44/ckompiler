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
               val target: BasicBlock,
               val other: BasicBlock) : CFGTerminator()

class UncondJump(val target: BasicBlock) : CFGTerminator()

class Return(val value: Expression?) : CFGTerminator()

class BasicBlock(val data: MutableList<ASTNode>,
                 val preds: List<BasicBlock>,
                 var term: CFGTerminator?) : CFGNode() {
  fun graphDataOf(): Pair<List<CFGNode>, List<Edge>> {
    val nodes = mutableListOf<CFGNode>()
    val edges = mutableListOf<Edge>()
    graphDataImpl(nodes, edges)
    return Pair(nodes.distinct(), edges.distinct())
  }

  private fun graphDataImpl(nodes: MutableList<CFGNode>, edges: MutableList<Edge>) {
    nodes.add(this)
    if (term == null) return
    nodes.add(term!!)
    when (term) {
      is UncondJump -> {
        val target = (term!! as UncondJump).target
        edges.add(Edge(this, target))
        target.graphDataImpl(nodes, edges)
      }
      is CondJump -> {
        edges.add(Edge(this, term!!))
        val t = term as CondJump
        edges.add(Edge(t, t.target))
        t.target.graphDataImpl(nodes, edges)
        edges.add(Edge(t, t.other))
        t.other.graphDataImpl(nodes, edges)
      }
      is Return -> {
        edges.add(Edge(this, term!!))
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
  val init = BasicBlock(mutableListOf(), emptyList(), null)
  graphCompound(init, f.block)
  return init
}

fun graphCompound(current: BasicBlock, compoundStatement: CompoundStatement): BasicBlock {
  var block = current
  for (item in compoundStatement.items) {
    when (item) {
      is StatementItem -> {
        block = graphStatement(block, item.statement)
      }
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
    current.term = CondJump(s.cond, ifBlock, elseBlock)
    val ifNext = graphStatement(ifBlock, s.success)
    if (ifNext.term == null) ifNext.term = UncondJump(afterIfBlock)
    s.failure?.let {
      val elseNext = graphStatement(elseBlock, it)
      if (elseNext.term == null) elseNext.term = UncondJump(afterIfBlock)
    }
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
