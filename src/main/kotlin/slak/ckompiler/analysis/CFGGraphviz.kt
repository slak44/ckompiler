package slak.ckompiler.analysis

import slak.ckompiler.analysis.GraphvizColors.*
import slak.ckompiler.parser.ASTNode

private enum class EdgeType {
  NORMAL, COND_TRUE, COND_FALSE
}

private data class Edge(val from: CFGNode, val to: CFGNode, val type: EdgeType = EdgeType.NORMAL)

private fun BasicBlock.graphDataOf(): Pair<List<CFGNode>, List<Edge>> {
  val nodes = mutableListOf<CFGNode>()
  val edges = mutableListOf<Edge>()
  graphDataImpl(nodes, edges)
  return Pair(nodes, edges.distinct())
}

/** Implementation detail of [graphDataOf]. Recursive case. */
private fun BasicBlock.graphDataImpl(nodes: MutableList<CFGNode>, edges: MutableList<Edge>) {
  // The graph can be cyclical, and we don't want to enter an infinite loop
  if (this in nodes) return
  nodes += this
  if (!isTerminated()) return
  nodes += terminator
  when (terminator) {
    is UncondJump -> {
      val target = (terminator as UncondJump).target
      edges += Edge(this, target)
      target.graphDataImpl(nodes, edges)
    }
    is CondJump -> {
      val t = terminator as CondJump
      edges += Edge(this, terminator)
      edges += Edge(terminator, t.target, EdgeType.COND_TRUE)
      t.target.graphDataImpl(nodes, edges)
      edges += Edge(terminator, t.other, EdgeType.COND_FALSE)
      t.other.graphDataImpl(nodes, edges)
    }
    is Return -> {
      (terminator as Return).deadCode?.also { if (it.data.isNotEmpty()) nodes += it }
      edges += Edge(this, terminator)
    }
  }
}

private enum class GraphvizColors(val color: String) {
  BG("\"#3C3F41ff\""), BLOCK_DEFAULT("\"#ccccccff\""),
  BLOCK_START("powderblue"), BLOCK_RETURN("mediumpurple2"),
  COND_TRUE("darkolivegreen3"), COND_FALSE("lightcoral");

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
    when (it) {
      is BasicBlock -> {
        val style = when {
          it.isStart() -> "style=filled,color=$BLOCK_START"
          else -> "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
        }
        val code = it.data.joinToString("\n") { node -> node.originalCode(sourceCode) }
        "${it.id} [shape=box,$style,label=\"${if (code.isBlank()) "<EMPTY>" else code}\"];"
      }
      is UncondJump -> "// unconditional jump ${it.id}"
      is CondJump -> {
        val color = "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
        "${it.id} [shape=diamond,$color,label=\"${it.cond?.originalCode(sourceCode)}\"];"
      }
      is Return -> {
        val style = "shape=ellipse,style=filled,color=$BLOCK_RETURN"
        "${it.id} [$style,label=\"${it.value?.originalCode(sourceCode)}\"];"
      }
      is Unterminated -> "// unterminated ${it.id}"
    }
  } + sep + edges.joinToString(sep) {
    val data = when (it.type) {
      EdgeType.NORMAL -> "color=$BLOCK_DEFAULT"
      EdgeType.COND_TRUE -> "color=$COND_TRUE"
      EdgeType.COND_FALSE -> "color=$COND_FALSE"
    }
    "${it.from.id} -> ${it.to.id} [$data];"
  }
  return "digraph CFG {${sep}bgcolor=$BG$sep$content\n}"
}
