package slak.ckompiler

import slak.ckompiler.analysis.*
import slak.ckompiler.backend.x64.X64TargetOpts
import slak.ckompiler.lexer.IncludePaths
import slak.ckompiler.lexer.Preprocessor
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser

@Suppress("ArrayInDataClass")
@JsExport
data class JSCompileResult(val cfgs: Array<CFG>?, val beforeCFGDiags: Array<Diagnostic>)

@JsExport
fun jsCompile(source: String, skipSSARename: Boolean): JSCompileResult {
  val includePaths = IncludePaths(emptyList(), listOf(FSPath(stdlibDir)), emptyList())
  val sourceFileName = "editor.c"
  val pp = Preprocessor(
      sourceText = source,
      srcFileName = sourceFileName,
      currentDir = FSPath("/"),
      cliDefines = emptyMap(),
      includePaths = includePaths,
      ignoreTrigraphs = true,
      targetData = MachineTargetData.x64
  )

  if (pp.diags.errors().isNotEmpty()) {
    return JSCompileResult(null, pp.diags.toTypedArray())
  }

  val p = Parser(pp.tokens, "-", source, MachineTargetData.x64)
  val beforeCFGDiags = pp.diags + p.diags

  if (p.diags.errors().isNotEmpty()) {
    return JSCompileResult(null, beforeCFGDiags.toTypedArray())
  }

  val allFuncs = p.root.decls.mapNotNull { it as? FunctionDefinition }

  val cfgs = allFuncs.map {
    val options = CFGOptions(forceReturnZero = it.name == "main", skipSSARename = skipSSARename)

    CFG(
        f = it,
        targetData = MachineTargetData.x64,
        srcFileName = sourceFileName,
        srcText = source,
        cfgOptions = options
    )
  }

  return JSCompileResult(cfgs.toTypedArray(), beforeCFGDiags.toTypedArray())
}

@JsExport
fun phiEligibleVariables(cfg: CFG): Array<Variable> {
  val stackVarIds = cfg.stackVariables.map { it.id }
  return cfg.exprDefinitions.filter { it.key.identityId !in stackVarIds }.map { it.key }.toTypedArray()
}

@JsExport
fun definitionsOf(variable: Variable, cfg: CFG): Array<BasicBlock> {
  return cfg.exprDefinitions[variable]!!.toTypedArray()
}

@JsExport
data class DiagnosticsStats(val warnings: Int, val errors: Int)

@JsExport
fun getDiagnosticsStats(diagnostics: Array<Diagnostic>): DiagnosticsStats {
  val warnings = diagnostics.count { it.id.kind == DiagnosticKind.WARNING }
  val errors = diagnostics.count { it.id.kind == DiagnosticKind.ERROR }

  return DiagnosticsStats(warnings, errors)
}

@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun closedRangeLength(closedRange: ClosedRange<Int>): Int {
  return closedRange.length()
}

@JsExport
fun diagnosticKindString(diagnostic: Diagnostic): String {
  return diagnostic.id.kind.name
}

@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun <T> arrayOf(collection: Collection<T>): Array<T> {
  return collection.toTypedArray()
}

@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun <T> arrayOfIterator(iterator: Iterator<T>): Array<T> {
  return iterator.asSequence().toList().toTypedArray()
}

@JsExport
fun BasicBlock.irToString(): String {
  val phi = phi.joinToString("\n").let { if (it.isBlank()) "" else "$it\n" }
  val blockCode = ir.joinToString("\n").let { if (it.isBlank()) "" else "$it\n" }
  val termCode = when (val term = terminator) {
    is CondJump -> term.cond.joinToString("\n") + " ?"
    is SelectJump -> term.cond.joinToString("\n") + " ?"
    is ImpossibleJump -> {
      if (term.returned == null) "return;"
      else "return ${term.returned.joinToString("\n")};"
    }
    else -> ""
  }
  return phi + blockCode + termCode
}

@JsExport
val codePrintingMethods: Array<String> = CodePrintingMethods.values().map { it.name }.toTypedArray()

@JsExport
fun getCodePrintingNameJs(print: String): String = getCodePrintingName(CodePrintingMethods.valueOf(print))

fun getCodePrintingName(print: CodePrintingMethods): String = when (print) {
  CodePrintingMethods.SOURCE_SUBSTRING -> "Source substrings"
  CodePrintingMethods.EXPR_TO_STRING -> "Expressions"
  CodePrintingMethods.IR_TO_STRING -> "IR"
  CodePrintingMethods.MI_TO_STRING -> "Machine instructions"
  CodePrintingMethods.ASM_TO_STRING -> "Assembly"
}

@JsExport
fun graphvizOptions(
    reachableOnly: Boolean,
    fontSize: Int,
    fontName: String,
    print: String,
    includeBlockHeader: Boolean,
): GraphvizOptions {
  return GraphvizOptions(
      fontSize = fontSize,
      fontName = fontName,
      reachableOnly = reachableOnly,
      print = CodePrintingMethods.valueOf(print),
      includeBlockHeader = includeBlockHeader,
      targetOpts = X64TargetOpts.defaults
  )
}
