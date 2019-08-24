package slak.ckompiler.analysis

import slak.ckompiler.analysis.GraphvizColors.*

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
      MissingJump -> {
        // Do nothing intentionally
      }
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

enum class CodePrintingMethods {
  SOURCE_SUBSTRING, EXPRESSION_TO_STRING, IR_EXPRESSION_TO_STRING
}

private fun IRLoweringContext.joinToString(sourceCode: String, print: CodePrintingMethods): String {
  return if (print == CodePrintingMethods.IR_EXPRESSION_TO_STRING) {
    ir.joinToString("\n")
  } else {
    src.joinToString("\n") {
      when (print) {
        CodePrintingMethods.SOURCE_SUBSTRING -> sourceCode.substring(it.range).trim()
        CodePrintingMethods.EXPRESSION_TO_STRING -> it.toString()
        else -> throw IllegalStateException("The other case is checked above")
      }
    }
  }
}

/**
 * Pretty graph for debugging purposes.
 * Possible usages:
 * ```
 * ckompiler --cfg-mode --display-graph
 * ckompiler --cfg-mode /tmp/file.c 2> /dev/null | dot -Tpng > /tmp/CFG.png && xdg-open /tmp/CFG.png
 * ```
 */
fun createGraphviz(graph: CFG,
                   sourceCode: String,
                   reachableOnly: Boolean,
                   print: CodePrintingMethods): String {
  val edges = graph.graphEdges()
  val sep = "\n  "
  val content = (if (reachableOnly) graph.nodes else graph.allNodes).joinToString(sep) {
    val style = when {
      it.isRoot -> "style=filled,color=$BLOCK_START"
      it.terminator is ImpossibleJump -> "style=filled,color=$BLOCK_RETURN"
      else -> "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
    }

    val phi = it.phiFunctions.joinToString("\n") { p -> p.toString() }
    val rawCode = it.irContext.joinToString(sourceCode, print)

    val cond = (it.terminator as? CondJump)?.cond?.let { context ->
      "\n${context.joinToString(sourceCode, print)} ?"
    } ?: ""
    val ret = (it.terminator as? ImpossibleJump)?.returned?.let { context ->
      "\nreturn ${context.joinToString(sourceCode, print)};"
    } ?: ""

    val code = phi + (if (phi.isNotBlank()) "\n" else "") + rawCode + cond + ret
    val blockText = if (code.isBlank()) "<EMPTY>" else code.trim()
    val escapedQuotes = blockText.replace("\"", "\\\"")
    "node${it.nodeId} [shape=box,$style,label=\"$escapedQuotes\"];"
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
