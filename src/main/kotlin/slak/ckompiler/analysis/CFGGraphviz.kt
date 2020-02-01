package slak.ckompiler.analysis

import slak.ckompiler.analysis.GraphvizColors.*
import slak.ckompiler.backend.regAlloc
import slak.ckompiler.backend.stringify
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
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
  SOURCE_SUBSTRING, EXPR_TO_STRING, IR_TO_STRING, MI_TO_STRING, ASM_TO_STRING
}

fun BasicBlock.srcToString(exprToStr: Expression.() -> String): String {
  fun List<Expression>.sourceSubstr() = joinToString("\n") { it.exprToStr() }
  val blockCode = src.sourceSubstr()
  val termCode = when (val term = terminator) {
    is CondJump -> term.src.exprToStr() + " ?"
    is SelectJump -> term.src.exprToStr() + " ?"
    is ImpossibleJump -> {
      if (term.returned == null) "return;"
      else "return ${term.src!!.exprToStr()};"
    }
    else -> ""
  }.let { if (it.isBlank()) "" else "\n$it" }
  return blockCode + termCode
}

fun BasicBlock.irToString(): String {
  val phi = phi.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" }
  val blockCode = ir.joinToString("\n")
  val termCode = when (val term = terminator) {
    is CondJump -> term.cond.joinToString("\n") + " ?"
    is SelectJump -> term.cond.joinToString("\n") + " ?"
    is ImpossibleJump -> {
      if (term.returned == null) "return;"
      else "return ${term.returned.joinToString("\n")};"
    }
    else -> ""
  }.let { if (it.isBlank()) "" else "\n$it" }
  return phi + blockCode + termCode
}

private fun CFG.mapBlocksToString(
    print: CodePrintingMethods,
    sourceCode: String
): Map<BasicBlock, String> {
  if (print == CodePrintingMethods.MI_TO_STRING) {
    val (newLists, _, _) = X64Target.regAlloc(this, X64Generator(this).instructionSelection())
    return newLists.mapValues { it.value.stringify("\\l") }
  } else if (print == CodePrintingMethods.ASM_TO_STRING) {
    val gen = X64Generator(this)
    val alloc = X64Target.regAlloc(this, gen.instructionSelection())
    return gen.applyAllocation(alloc).mapValues { it.value.joinToString("\\l") }
  }
  return allNodes.associateWith {
    when (print) {
      CodePrintingMethods.SOURCE_SUBSTRING -> it.srcToString {
        (sourceText ?: sourceCode).substring(range).trim()
      }
      CodePrintingMethods.EXPR_TO_STRING -> it.srcToString { it.toString() }
      CodePrintingMethods.IR_TO_STRING -> it.irToString()
      else -> throw IllegalStateException("Unreachable")
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
  val blockMap = graph.mapBlocksToString(print, sourceCode)
  val content = (if (reachableOnly) graph.nodes else graph.allNodes).joinToString(sep) {
    val style = when {
      it.isRoot -> "style=filled,color=$BLOCK_START"
      it.terminator is ImpossibleJump -> "style=filled,color=$BLOCK_RETURN"
      else -> "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
    }
    val code = blockMap.getValue(it)
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
