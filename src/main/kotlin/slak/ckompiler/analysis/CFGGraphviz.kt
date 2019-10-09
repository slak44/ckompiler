package slak.ckompiler.analysis

import slak.ckompiler.analysis.GraphvizColors.*
import slak.ckompiler.parser.Expression

private enum class EdgeType {
  NORMAL, COND_TRUE, COND_FALSE,
  /** An edge that might be traversed based on what value is. */
  COND_MAYBE,
  /** An edge that cannot ever be traversed. */
  IMPOSSIBLE
}

private data class Edge(
    val from: BasicBlock,
    val to: BasicBlock,
    val type: EdgeType = EdgeType.NORMAL,
    val text: String = ""
)

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

private enum class GraphvizColors(val color: String) {
  BG("\"#3C3F41ff\""), BLOCK_DEFAULT("\"#ccccccff\""),
  BLOCK_START("powderblue"), BLOCK_RETURN("mediumpurple2"),
  COND_TRUE("darkolivegreen3"), COND_FALSE("lightcoral"),
  COND_MAYBE("\"#D7BA4A\""),
  IMPOSSIBLE("black");

  override fun toString() = color
}

enum class CodePrintingMethods {
  SOURCE_SUBSTRING, EXPRESSION_TO_STRING, IR_EXPRESSION_TO_STRING
}

private fun Pair<List<IRInstruction>, List<Expression>>.joinToString(
    sourceCode: String,
    print: CodePrintingMethods
): String {
  return if (print == CodePrintingMethods.IR_EXPRESSION_TO_STRING) {
    first.joinToString("\n")
  } else {
    second.joinToString("\n") {
      when (print) {
        CodePrintingMethods.SOURCE_SUBSTRING -> {
          (it.sourceText ?: sourceCode).substring(it.range).trim()
        }
        CodePrintingMethods.EXPRESSION_TO_STRING -> it.toString()
        else -> throw IllegalStateException("The other case is checked above")
      }
    }
  }
}

private fun String.unescape(): String =
    replace("\"", "\\\"")
        .replace("\\b", "\\\\b")
        .replace("\\t", "\\\\t")
        .replace("\\n", "\\\\n")

/**
 * Pretty graph for debugging purposes.
 * Possible usages:
 * ```
 * ckompiler --cfg-mode --display-graph
 * ckompiler --cfg-mode /tmp/file.c 2> /dev/null | dot -Tpng > /tmp/CFG.png && xdg-open /tmp/CFG.png
 * ```
 */
fun createGraphviz(
    graph: CFG,
    sourceCode: String,
    reachableOnly: Boolean,
    print: CodePrintingMethods
): String {
  val edges = graph.graphEdges()
  val sep = "\n  "
  val content = (if (reachableOnly) graph.nodes else graph.allNodes).joinToString(sep) {
    val style = when {
      it.isRoot -> "style=filled,color=$BLOCK_START"
      it.terminator is ImpossibleJump -> "style=filled,color=$BLOCK_RETURN"
      else -> "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
    }

    val phi = if (print == CodePrintingMethods.IR_EXPRESSION_TO_STRING) {
      it.phiFunctions.joinToString("\n") { p -> p.toString() }
    } else {
      ""
    }
    val rawCode = (it.ir to it.src).joinToString(sourceCode, print)

    val cond = (it.terminator as? CondJump)?.let { (cond, src) ->
      "\n${(cond to listOf(src)).joinToString(sourceCode, print)} ?"
    } ?: ""
    val ret = (it.terminator as? ImpossibleJump)?.let { (_, returned, src) ->
      if (returned == null) ""
      else "\nreturn ${(returned to listOf(src!!)).joinToString(sourceCode, print)};"
    } ?: ""

    val code = phi + (if (phi.isNotBlank()) "\n" else "") + rawCode + cond + ret
    val blockText = if (code.isBlank()) "<EMPTY>" else code.trim()
    "node${it.hashCode()} [shape=box,$style,label=\"${blockText.unescape()}\"];"
  } + sep + edges.joinToString(sep) {
    val color = when (it.type) {
      EdgeType.NORMAL -> "color=$BLOCK_DEFAULT"
      EdgeType.COND_TRUE -> "color=$COND_TRUE"
      EdgeType.COND_FALSE -> "color=$COND_FALSE"
      EdgeType.COND_MAYBE -> "color=$COND_MAYBE"
      EdgeType.IMPOSSIBLE -> "color=$IMPOSSIBLE"
    }
    if (reachableOnly && !it.to.isReachable()) {
      ""
    } else {
      val props = "$color,label=\"${it.text.unescape()}\",fontcolor=$BLOCK_DEFAULT"
      "node${it.from.hashCode()} -> node${it.to.hashCode()} [$props];"
    }
  }
  return "digraph CFG {${sep}bgcolor=$BG$sep$content\n}"
}
