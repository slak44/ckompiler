package slak.ckompiler

import kotlinx.serialization.encodeToString
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.CFGOptions
import slak.ckompiler.analysis.Variable
import slak.ckompiler.analysis.external.json
import slak.ckompiler.backend.ISAType
import slak.ckompiler.lexer.IncludePaths
import slak.ckompiler.lexer.Preprocessor
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser

@Suppress("ArrayInDataClass", "NON_EXPORTABLE_TYPE")
@JsExport
data class JSCompileResult(val cfgs: Array<CFG>?, val beforeCFGDiags: Array<Diagnostic>, var atomicCounters: SavedAtomics)

@JsExport
fun jsCompile(source: String, skipSSARename: Boolean, isaType: ISAType): JSCompileResult {
  val includePaths = IncludePaths(emptyList(), listOf(FSPath(stdlibDir)), emptyList())
  val sourceFileName = "editor.c"
  val pp = Preprocessor(
      sourceText = source,
      srcFileName = sourceFileName,
      currentDir = FSPath("/"),
      cliDefines = emptyMap(),
      includePaths = includePaths,
      ignoreTrigraphs = true,
      targetData = isaType.machineTargetData
  )

  if (pp.diags.errors().isNotEmpty()) {
    return JSCompileResult(null, pp.diags.toTypedArray(), saveAndClearAllAtomicCounters())
  }

  val p = Parser(pp.tokens, "-", source, isaType.machineTargetData)
  val beforeCFGDiags = pp.diags + p.diags

  if (p.diags.errors().isNotEmpty()) {
    return JSCompileResult(null, beforeCFGDiags.toTypedArray(), saveAndClearAllAtomicCounters())
  }

  val allFuncs = p.root.decls.mapNotNull { it as? FunctionDefinition }

  val cfgs = allFuncs.map {
    val options = CFGOptions(forceReturnZero = it.name == "main", skipSSARename = skipSSARename)

    CFG(
        f = it,
        targetData = isaType.machineTargetData,
        srcFileName = sourceFileName,
        srcText = source,
        cfgOptions = options
    )
  }

  return JSCompileResult(cfgs.toTypedArray(), beforeCFGDiags.toTypedArray(), saveAndClearAllAtomicCounters())
}

@JsExport
fun phiEligibleVariables(cfg: CFG): Array<Variable> {
  return cfg.exprDefinitions.filter { it.key.identityId !in cfg.stackVariableIds }.map { it.key }.toTypedArray()
}

@JsExport
fun definitionsOf(variable: Variable, cfg: CFG): Array<BasicBlock> {
  return cfg.exprDefinitions[variable]!!.toTypedArray()
}

@JsExport
fun getDefinitionLocations(variable: Variable, cfg: CFG): String {
  val definitionIdx = mutableMapOf<AtomicId, Int>()

  for (defBlock in cfg.exprDefinitions[variable]!!) {
    val index = defBlock.ir.indexOfLast { it.result is Variable && (it.result as Variable).identityId == variable.identityId }
    definitionIdx[defBlock.nodeId] = index
  }

  return json.encodeToString(definitionIdx)
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

@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun <T> arrayOfCollection(collection: Collection<T>): Array<T> {
  return collection.toTypedArray()
}

@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun <T> arrayOfIterator(iterator: Iterator<T>): Array<T> {
  return iterator.asSequence().toList().toTypedArray()
}
