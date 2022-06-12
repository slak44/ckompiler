package slak.ckompiler.analysis.external

import mu.KotlinLogging
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import slak.ckompiler.analysis.external.GraphvizColors.*
import slak.ckompiler.backend.insertSpillReloadCode
import slak.ckompiler.backend.regAlloc
import slak.ckompiler.backend.runSpiller
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.backend.x64.X64TargetOpts
import slak.ckompiler.error
import slak.ckompiler.parser.Expression
import slak.ckompiler.parser.Terminal
import kotlin.js.JsExport

private val logger = KotlinLogging.logger {}

private enum class GraphvizColors(val color: String) {
  BG("\"#3C3F41ff\""), BLOCK_DEFAULT("\"#ccccccff\""),
  BLOCK_START("\"#FFFF00ff\""), BLOCK_RETURN("\"#9C27B0ff\""),
  COND_TRUE("darkolivegreen3"), COND_FALSE("lightcoral"),
  COND_MAYBE("\"#D7BA4A\""),
  IMPOSSIBLE("black"),
  MI_COLOR_A("#ed8e45"), MI_COLOR_B("#3480eb");

  override fun toString() = color
}

@JsExport
enum class CodePrintingMethods {
  SOURCE_SUBSTRING, EXPR_TO_STRING, IR_TO_STRING, MI_TO_STRING, ASM_TO_STRING
}

private const val brLeft = "<br align=\"left\"/>"

fun blockHeader(id: AtomicId): String = "<span class=\"header\">BB$id:</span>".unescape() + brLeft

fun BasicBlock.srcToString(exprToStr: Expression.() -> String): String {
  fun List<Expression>.sourceSubstr() = joinToString(brLeft) { it.exprToStr() }
  val blockCode = src.filter { it !is Terminal }.sourceSubstr()
  val termCode = when (val term = terminator) {
    is CondJump -> term.src.exprToStr() + " ?$brLeft"
    is SelectJump -> term.src.exprToStr() + " ?$brLeft"
    is ImpossibleJump -> {
      if (term.returned == null) "return;$brLeft"
      else "return ${term.src!!.exprToStr()};$brLeft"
    }
    else -> ""
  }
  val separator = if (blockCode.isNotBlank()) brLeft else ""
  return blockCode + separator + termCode
}

fun BasicBlock.irToString(): String {
  val phi = phi.joinToString(brLeft) { it.toString().unescape() }
      .let { if (it.isBlank()) "" else "$it$brLeft" }
  val blockCode = ir.joinToString(brLeft).let { if (it.isBlank()) "" else "$it$brLeft" }
  val termCode = when (val term = terminator) {
    is CondJump -> term.cond.joinToString(brLeft, postfix = " ?$brLeft") { it.toString().unescape() }
    is SelectJump -> term.cond.joinToString(brLeft, postfix = " ?$brLeft") { it.toString().unescape() }
    is ImpossibleJump -> {
      if (term.returned == null) {
        "return;$brLeft"
      } else {
        val last = "return ${term.returned.last().toString().unescape()};"
        val notLast = term.returned.dropLast(1).joinToString(brLeft) { it.toString().unescape() }

        if (notLast.isEmpty()) {
          last + brLeft
        } else {
          "$notLast$brLeft$last$brLeft"
        }
      }
    }
    else -> ""
  }
  return phi + blockCode + termCode
}

private fun CFG.mapBlocksToString(sourceCode: String, options: GraphvizOptions): Map<BasicBlock, String> {
  val target = X64Target(options.targetOpts)
  val sep = brLeft
  if (options.print == CodePrintingMethods.MI_TO_STRING) {
    val gen = X64Generator(this, target)
    val graph = try {
      if (options.noAllocOnlySpill) {
        val spillResult = gen.runSpiller()
        gen.insertSpillReloadCode(spillResult)
        gen.graph
      } else {
        gen.regAlloc().graph
      }
    } catch (e: Exception) {
      logger.error("Reg alloc failed, fall back to initial graph", e)
      gen.graph
    }
    val nodes = graph.domTreePreorder.asSequence().toList()
    val instrGraphMap = nodes.associateWith {
      val phiStr = graph[it].phi.entries.joinToString(separator = sep) { (variable, uses) ->
        val phiOptions = uses.entries.joinToString(", ") { (predId, variable) ->
          "BB$predId ${variable.versionString()}"
        }
        "$variable = φ($phiOptions)"
      }

      val miStr = graph[it].joinToString(separator = sep, postfix = sep) { mi ->
        val color = if (mi.irLabelIndex % 2 == 0) MI_COLOR_A else MI_COLOR_B
        "<font color=\"$color\">${mi.toString().unescape().replace("\n", sep)}</font>"
      }

      val content = if (phiStr.isNotBlank()) phiStr + sep + miStr else miStr

      return@associateWith ".block$it:$brLeft$content"
    }
    return instrGraphMap.mapKeys { (blockId) -> allNodes.firstOrNull { it.nodeId == blockId } ?: newBlock() }
  } else if (options.print == CodePrintingMethods.ASM_TO_STRING) {
    val gen = X64Generator(this, target)
    val alloc = gen.regAlloc()
    return gen.applyAllocation(alloc).mapValues {
      ".block${it.key}:$brLeft" + it.value.joinToString(separator = sep, postfix = sep)
    }.mapKeys { (blockId) -> allNodes.firstOrNull { it.nodeId == blockId } ?: newBlock() }
  }
  return allNodes.associateWith {
    val maybeHeader =
        if (options.includeBlockHeader && options.print != CodePrintingMethods.SOURCE_SUBSTRING) blockHeader(it.nodeId) else ""

    val content = when (options.print) {
      CodePrintingMethods.SOURCE_SUBSTRING -> it.srcToString {
        (sourceText ?: sourceCode).substring(range).trim().unescape()
      }
      CodePrintingMethods.EXPR_TO_STRING -> it.srcToString { toString().unescape() }
      CodePrintingMethods.IR_TO_STRING -> it.irToString()
      else -> throw IllegalStateException("Unreachable")
    }

    (maybeHeader + content).ifEmpty { "&lt;empty&gt;" }
  }
}

private fun String.unescape(): String =
    replace("\\b", "\\\\b")
        .replace("\\t", "\\\\t")
        .replace("\\n", "\\\\n")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

@JsExport
data class GraphvizOptions(
    val fontSize: Int = 14,
    val fontName: String? = null,
    val reachableOnly: Boolean,
    val print: CodePrintingMethods = CodePrintingMethods.IR_TO_STRING,
    val includeBlockHeader: Boolean = false,
    val targetOpts: X64TargetOpts = X64TargetOpts.defaults,
    val noAllocOnlySpill: Boolean = false
)

/**
 * Pretty graph for debugging purposes.
 * Possible usages:
 * ```
 * ckompiler --cfg-mode --display-graph
 * ckompiler --cfg-mode /tmp/file.c 2> /dev/null | dot -Tpng > /tmp/CFG.png && xdg-open /tmp/CFG.png
 * ```
 */
@JsExport
fun createGraphviz(graph: CFG, sourceCode: String, options: GraphvizOptions): String {
  val edges = graph.graphEdges()
  val sep = "\n  "
  val blockMap = graph.mapBlocksToString(sourceCode, options)
  val content = (if (options.reachableOnly) graph.nodes else graph.allNodes).joinToString(sep) {
    val style = when {
      it.isRoot -> "style=solid,penwidth=3,color=$BLOCK_START,fontcolor=$BLOCK_DEFAULT"
      it.terminator is ImpossibleJump -> "style=solid,penwidth=5,color=$BLOCK_RETURN,fontcolor=$BLOCK_DEFAULT"
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
    if (options.reachableOnly && !it.to.isReachable()) {
      ""
    } else {
      val props = "$color,label=\"${it.text.unescape()}\",fontcolor=$BLOCK_DEFAULT"
      "node${it.from.hashCode()} -> node${it.to.hashCode()} [$props];"
    }
  }

  val fontName = "fontname=\"${options.fontName}\""
  val maybeFont = if (options.fontName == null) "" else "$fontName,"
  val graphAttrs = listOf(
      maybeFont.dropLastWhile { it == ',' },
      "bgcolor=$BG",
      "fontsize=${options.fontSize}"
  ).filter { it.isNotBlank() }
  val graphAttr = "graph[${graphAttrs.joinToString(",")}];"
  val nodeAttr = "node[${maybeFont}fontsize=${options.fontSize}]"

  return "digraph CFG {$sep$graphAttr$sep$nodeAttr$sep$content\n}"
}
