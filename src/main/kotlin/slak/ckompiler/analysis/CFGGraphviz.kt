package slak.ckompiler.analysis

import slak.ckompiler.analysis.GraphvizColors.*
import slak.ckompiler.parser.ASTNode

private enum class EdgeType {
  NORMAL, COND_TRUE, COND_FALSE,
  /** An edge that cannot ever be traversed. */
  IMPOSSIBLE
}

private data class Edge(val from: BasicBlock,
                        val to: BasicBlock,
                        val type: EdgeType = EdgeType.NORMAL)

private fun CFG.graphEdges(): List<Edge> {
  val edges = mutableListOf<Edge>()
  for (node in nodes) {
    when (node.terminator) {
      is UncondJump, is ImpossibleJump -> {
        val target =
            if (node.terminator is UncondJump) (node.terminator as UncondJump).target
            else (node.terminator as ImpossibleJump).target
        val edgeType =
            if (node.terminator is ImpossibleJump) EdgeType.IMPOSSIBLE else EdgeType.NORMAL
        edges += Edge(node, target, edgeType)
      }
      is CondJump -> {
        val t = node.terminator as CondJump
        edges += Edge(node, t.target, EdgeType.COND_TRUE)
        edges += Edge(node, t.other, EdgeType.COND_FALSE)
      }
      is ConstantJump -> {
        val t = node.terminator as ConstantJump
        edges += Edge(node, t.target)
        edges += Edge(node, t.impossible, EdgeType.IMPOSSIBLE)
      }
      MissingJump -> { /* Do nothing intentionally */ }
    }
  }
  return edges.distinct()
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
fun createGraphviz(graph: CFG, sourceCode: String, reachableOnly: Boolean): String {
  val edges = graph.graphEdges()
  val sep = "\n  "
  val content = (if (reachableOnly) graph.nodes else graph.allNodes).joinToString(sep) {
    val style = when {
      it.isRoot -> "style=filled,color=$BLOCK_START"
      it.terminator is ImpossibleJump -> "style=filled,color=$BLOCK_RETURN"
      else -> "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
    }

    val defs = it.definitions.joinToString("\n") { (name, type) -> "$type $name;" }
    val rawCode = it.data.joinToString("\n") { node -> node.originalCode(sourceCode) }

    val cond = (it.terminator as? CondJump)?.cond?.let { cond ->
      "\n${cond.originalCode(sourceCode)} ?"
    } ?: ""
    val ret = (it.terminator as? ImpossibleJump)?.returned?.let { ret ->
      "\nreturn ${ret.originalCode(sourceCode)};"
    } ?: ""

    val code = defs + (if (defs.isNotBlank()) "\n" else "") + rawCode + cond + ret
    val blockText = if (code.isBlank()) "<EMPTY>" else code.trim()
    "node${it.nodeId} [shape=box,$style,label=\"$blockText\"];"
  } + sep + edges.joinToString(sep) {
    val color = when (it.type) {
      EdgeType.NORMAL -> "color=$BLOCK_DEFAULT"
      EdgeType.COND_TRUE -> "color=$COND_TRUE"
      EdgeType.COND_FALSE -> "color=$COND_FALSE"
      EdgeType.IMPOSSIBLE -> "color=$IMPOSSIBLE"
    }
    if (reachableOnly && !it.to.isReachable()) {
      ""
    } else {
      "node${it.from.nodeId} -> node${it.to.nodeId} [$color];"
    }
  }
  return "digraph CFG {${sep}bgcolor=$BG$sep$content\n}"
}
