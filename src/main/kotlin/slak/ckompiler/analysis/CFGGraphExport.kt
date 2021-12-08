package slak.ckompiler.analysis

enum class EdgeType {
  NORMAL, COND_TRUE, COND_FALSE,

  /** An edge that might be traversed based on what value is. */
  COND_MAYBE,

  /** An edge that cannot ever be traversed. */
  IMPOSSIBLE
}

data class Edge(
    val from: BasicBlock,
    val to: BasicBlock,
    val type: EdgeType = EdgeType.NORMAL,
    val text: String = ""
)

fun CFG.graphEdges(): List<Edge> {
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
      is SelectJump -> {
        val t = node.terminator as SelectJump
        edges += t.options.entries.map {
          Edge(node, it.value, EdgeType.COND_MAYBE, it.key.toString())
        }
        edges += Edge(node, t.default, EdgeType.COND_MAYBE, "default")
      }
      MissingJump -> {
        // Do nothing intentionally
      }
    }
  }
  return edges.distinct()
}
