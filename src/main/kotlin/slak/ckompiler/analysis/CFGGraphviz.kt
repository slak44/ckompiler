package slak.ckompiler.analysis

import slak.ckompiler.analysis.GraphvizColors.*
import slak.ckompiler.parser.ASTNode
import slak.ckompiler.parser.Expression

private enum class EdgeType {
  NORMAL, COND_TRUE, COND_FALSE,
  /** An edge that cannot ever be traversed. */
  IMPOSSIBLE
}

private data class Node(val basicBlock: BasicBlock, val cond: Expression? = null) {
  val id: String get() = "${javaClass.simpleName}_$objNr"
  private val objNr = objIndex++

  override fun equals(other: Any?) = objNr == (other as? Node)?.objNr
  override fun hashCode() = objNr

  companion object {
    private var objIndex = 0
  }
}

private data class Edge(val from: Node, val to: Node, val type: EdgeType = EdgeType.NORMAL)

private fun BasicBlock.graphDataOf(): Pair<List<Node>, List<Edge>> {
  val nodes = mutableListOf<Node>()
  val edges = mutableListOf<Edge>()
  graphDataImpl(nodes, edges)
  return Pair(nodes, edges.distinct())
}

/** Implementation detail of [graphDataOf]. Recursive case. */
private fun BasicBlock.graphDataImpl(nodes: MutableList<Node>, edges: MutableList<Edge>): Node {
  // The graph can be cyclical, and we don't want to enter an infinite loop
  val alreadyProcessedNode = nodes.firstOrNull { it.basicBlock == this }
  if (alreadyProcessedNode != null) return alreadyProcessedNode
  if (!isTerminated()) {
    val thisNode = Node(this)
    nodes += thisNode
    return thisNode
  }
  return when (terminator) {
    is UncondJump, is ImpossibleJump -> {
      val thisNode = Node(this)
      nodes += thisNode
      val target =
          if (terminator is UncondJump) (terminator as UncondJump).target
          else (terminator as ImpossibleJump).target
      val edgeType = if (terminator is ImpossibleJump) EdgeType.IMPOSSIBLE else EdgeType.NORMAL
      edges += Edge(thisNode, target.graphDataImpl(nodes, edges), edgeType)
      thisNode
    }
    is CondJump -> {
      val t = terminator as CondJump
      val thisNode = Node(this, t.cond)
      nodes += thisNode
      edges += Edge(thisNode, t.target.graphDataImpl(nodes, edges), EdgeType.COND_TRUE)
      edges += Edge(thisNode, t.other.graphDataImpl(nodes, edges), EdgeType.COND_FALSE)
      thisNode
    }
    is ConstantJump -> {
      val thisNode = Node(this)
      nodes += thisNode
      val t = terminator as ConstantJump
      edges += Edge(thisNode, t.target.graphDataImpl(nodes, edges))
      edges += Edge(thisNode, t.impossible.graphDataImpl(nodes, edges), EdgeType.IMPOSSIBLE)
      thisNode
    }
    MissingJump -> Node(BasicBlock())
  }
}

private enum class GraphvizColors(val color: String) {
  BG("\"#3C3F41ff\""), BLOCK_DEFAULT("\"#ccccccff\""),
  BLOCK_START("powderblue"), BLOCK_RETURN("mediumpurple2"),
  COND_TRUE("darkolivegreen3"), COND_FALSE("lightcoral"),
  IMPOSSIBLE("black");

  override fun toString() = color
}

/** Gets the piece of the source code that this node was created from. */
private fun ASTNode.originalCode(sourceCode: String) = sourceCode.substring(tokenRange).trim()

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
    val style = when {
      it.basicBlock.isRoot -> "style=filled,color=$BLOCK_START"
      it.basicBlock.isEnd() -> "style=filled,color=$BLOCK_RETURN"
      else -> "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
    }
    val rawCode = it.basicBlock.data.joinToString("\n") { node -> node.originalCode(sourceCode) }
    val code =
        if (it.cond == null) rawCode
        else "$rawCode${if (rawCode.isBlank()) "" else "\n"}${it.cond.originalCode(sourceCode)} ?"
    val blockText = if (code.isBlank()) "<EMPTY>" else code
    "${it.id} [shape=box,$style,label=\"$blockText\"];"
  } + sep + edges.joinToString(sep) {
    val color = when (it.type) {
      EdgeType.NORMAL -> "color=$BLOCK_DEFAULT"
      EdgeType.COND_TRUE -> "color=$COND_TRUE"
      EdgeType.COND_FALSE -> "color=$COND_FALSE"
      EdgeType.IMPOSSIBLE -> "color=$IMPOSSIBLE"
    }
    "${it.from.id} -> ${it.to.id} [$color];"
  }
  return "digraph CFG {${sep}bgcolor=$BG$sep$content\n}"
}
