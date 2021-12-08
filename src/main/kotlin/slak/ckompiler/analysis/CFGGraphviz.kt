package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.analysis.GraphvizColors.*
import slak.ckompiler.backend.regAlloc
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.backend.x64.X64TargetOpts
import slak.ckompiler.error
import slak.ckompiler.parser.Expression
import kotlin.js.JsExport

private val logger = KotlinLogging.logger {}

private enum class GraphvizColors(val color: String) {
  BG("\"#3C3F41ff\""), BLOCK_DEFAULT("\"#ccccccff\""),
  BLOCK_START("powderblue"), BLOCK_RETURN("mediumpurple2"),
  COND_TRUE("darkolivegreen3"), COND_FALSE("lightcoral"),
  COND_MAYBE("\"#D7BA4A\""),
  IMPOSSIBLE("black"),
  MI_COLOR_A("#ed8e45"), MI_COLOR_B("#3480eb");

  override fun toString() = color
}

enum class CodePrintingMethods {
  SOURCE_SUBSTRING, EXPR_TO_STRING, IR_TO_STRING, MI_TO_STRING, ASM_TO_STRING
}

fun BasicBlock.srcToString(exprToStr: Expression.() -> String): String {
  fun List<Expression>.sourceSubstr() = joinToString("<br/>") { it.exprToStr() }
  val blockCode = src.sourceSubstr()
  val termCode = when (val term = terminator) {
    is CondJump -> term.src.exprToStr() + " ?"
    is SelectJump -> term.src.exprToStr() + " ?"
    is ImpossibleJump -> {
      if (term.returned == null) "return;"
      else "return ${term.src!!.exprToStr()};"
    }
    else -> ""
  }.let { if (it.isBlank()) "" else "<br/>$it" }
  return blockCode + termCode
}

fun BasicBlock.irToString(): String {
  val phi = phi.joinToString("<br/>") { it.toString().unescape() }
      .let { if (it.isBlank()) "" else "$it<br/>" }
  val blockCode = ir.joinToString("<br/>").let { if (it.isBlank()) "" else "$it<br/>" }
  val termCode = when (val term = terminator) {
    is CondJump -> term.cond.joinToString("<br/>") { it.toString().unescape() } + " ?"
    is SelectJump -> term.cond.joinToString("<br/>") { it.toString().unescape() } + " ?"
    is ImpossibleJump -> {
      if (term.returned == null) "return;"
      else "return ${term.returned.joinToString("<br/>") { it.toString().unescape() }};"
    }
    else -> ""
  }
  return phi + blockCode + termCode
}

private fun CFG.mapBlocksToString(
    print: CodePrintingMethods,
    sourceCode: String,
    targetOpts: X64TargetOpts
): Map<BasicBlock, String> {
  val target = X64Target(targetOpts)
  val sep = "<br align=\"left\"/>"
  if (print == CodePrintingMethods.MI_TO_STRING) {
    val gen = X64Generator(this, target)
    val graph = try {
      gen.regAlloc().graph
    } catch (e: Exception) {
      logger.error("Reg alloc failed, fall back to initial graph", e)
      gen.graph
    }
    val nodes = graph.domTreePreorder.asSequence().toList()
    val instrGraphMap = nodes.associateWith {
      val phiStr = graph[it].phi.entries.joinToString(separator = sep) { (variable, uses) ->
        val options = uses.entries.joinToString(", ") { (predId, variable) ->
          "n$predId ${variable.versionString()}"
        }
        "$variable = φ($options)"
      }

      val miStr = graph[it].joinToString(separator = sep, postfix = sep) { mi ->
        val color = if (mi.irLabelIndex % 2 == 0) MI_COLOR_A else MI_COLOR_B
        "<font color=\"$color\">${mi.toString().unescape().replace("\n", sep)}</font>"
      }

      return@associateWith if (phiStr.isNotBlank()) {
        phiStr + sep + miStr
      } else {
        miStr
      }
    }
    return instrGraphMap.mapKeys { (blockId) -> allNodes.firstOrNull { it.nodeId == blockId } ?: newBlock() }
  } else if (print == CodePrintingMethods.ASM_TO_STRING) {
    val gen = X64Generator(this, target)
    val alloc = gen.regAlloc()
    return gen.applyAllocation(alloc).mapValues {
      it.value.joinToString(separator = sep, postfix = sep)
    }.mapKeys { (blockId) -> allNodes.firstOrNull { it.nodeId == blockId } ?: newBlock() }
  }
  return allNodes.associateWith {
    when (print) {
      CodePrintingMethods.SOURCE_SUBSTRING -> it.srcToString {
        (sourceText ?: sourceCode).substring(range).trim().unescape()
      }
      CodePrintingMethods.EXPR_TO_STRING -> it.srcToString { toString().unescape() }
      CodePrintingMethods.IR_TO_STRING -> it.irToString()
      else -> throw IllegalStateException("Unreachable")
    }
  }
}

private fun String.unescape(): String =
    replace("\\b", "\\\\b")
        .replace("\\t", "\\\\t")
        .replace("\\n", "\\\\n")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Pretty graph for debugging purposes.
 * Possible usages:
 * ```
 * ckompiler --cfg-mode --display-graph
 * ckompiler --cfg-mode /tmp/file.c 2> /dev/null | dot -Tpng > /tmp/CFG.png && xdg-open /tmp/CFG.png
 * ```
 */
@JsExport
fun createGraphviz(
    graph: CFG,
    sourceCode: String,
    reachableOnly: Boolean,
    print: CodePrintingMethods = CodePrintingMethods.IR_TO_STRING,
    targetOpts: X64TargetOpts = X64TargetOpts.defaults
): String {
  val edges = graph.graphEdges()
  val sep = "\n  "
  val blockMap = graph.mapBlocksToString(print, sourceCode, targetOpts)
  val content = (if (reachableOnly) graph.nodes else graph.allNodes).joinToString(sep) {
    val style = when {
      it.isRoot -> "style=filled,color=$BLOCK_START"
      it.terminator is ImpossibleJump -> "style=filled,color=$BLOCK_RETURN"
      else -> "color=$BLOCK_DEFAULT,fontcolor=$BLOCK_DEFAULT"
    }
    val code = blockMap.getValue(it)
    val blockText = if (code.isBlank()) "\"<EMPTY>\"" else code.trim()
    "node${it.hashCode()} [shape=box,$style,label=<$blockText>];"
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
