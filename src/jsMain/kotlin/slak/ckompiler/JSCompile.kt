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
fun jsCompile(source: String): JSCompileResult {
  val includePaths = IncludePaths(emptyList(), emptyList(), emptyList())
  val pp = Preprocessor(
      sourceText = source,
      srcFileName = "-",
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
    CFG(
        f = it,
        targetData = MachineTargetData.x64,
        srcFileName = "-",
        srcText = source,
        forceAllNodes = false,
        forceReturnZero = it.name == "main"
    )
  }

  return JSCompileResult(cfgs.toTypedArray(), beforeCFGDiags.toTypedArray())
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
fun graphvizOptions(reachableOnly: Boolean, fontSize: Int, fontName: String, print: String): GraphvizOptions {
  return GraphvizOptions(
      fontSize = fontSize,
      fontName = fontName,
      reachableOnly = reachableOnly,
      print = CodePrintingMethods.valueOf(print),
      targetOpts = X64TargetOpts.defaults
  )
}
