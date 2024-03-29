package slak.test

import slak.ckompiler.*
import slak.ckompiler.analysis.CFG
import slak.ckompiler.lexer.*
import slak.ckompiler.FSPath
import slak.ckompiler.analysis.CFGFactory
import slak.ckompiler.analysis.CFGOptions
import slak.ckompiler.parser.ExternalDeclaration
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal fun Preprocessor.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal fun Parser.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal fun IDebugHandler.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal val <T : Any> T.source get() = "<test/${this::class.simpleName}>"
internal fun <T : Any> T.resource(s: String) = File(javaClass.classLoader.getResource(s)!!.file)

internal fun preparePP(s: String, source: SourceFileName): Preprocessor {
  val incs = IncludePaths(
      emptyList(),
      listOf(FSPath(IncludePaths.resource("include")), FSPath(IncludePaths.resource("headers/system"))),
      listOf(FSPath(IncludePaths.resource("headers/users")))
  )

  val pp = Preprocessor(
      sourceText = s,
      srcFileName = source,
      includePaths = incs + IncludePaths.defaultPaths,
      targetData = MachineTargetData.x64,
      currentDir = FSPath(File("."))
  )

  pp.diags.forEach { it.print() }

  return pp
}

internal fun prepareCode(s: String, source: SourceFileName): Parser {
  val pp = preparePP(s, source)
  pp.assertNoDiagnostics()
  val p = Parser(pp.tokens, source, s, MachineTargetData.x64)
  p.diags.forEach { it.print() }
  return p
}

internal fun prepareCFG(s: String, source: SourceFileName, functionName: String? = null): CFGFactory {
  val p = prepareCode(s, source)
  p.assertNoDiagnostics()
  val func = if (functionName == null) {
    p.root.decls.firstFun()
  } else {
    p.root.decls.first { it is FunctionDefinition && it.name == functionName } as FunctionDefinition
  }

  val options = CFGOptions(forceReturnZero = true)

  return CFGFactory(func, MachineTargetData.x64, source, s, options)
}

fun prepareCFG(file: File, source: SourceFileName, functionName: String? = null): CFGFactory {
  return prepareCFG(file.readText(), source, functionName)
}

@JvmName("cli_array")
internal fun cli(args: Array<String>): Pair<CLI, ExitCodes> = cli({ System.`in`.readAllBytes() }, *args)

@JvmName("cli_vararg")
internal fun cli(vararg args: String): Pair<CLI, ExitCodes> = cli({ System.`in`.readAllBytes() }, *args)

internal fun cli(readStdin: () -> ByteArray, vararg args: String): Pair<CLI, ExitCodes> {
  val cli = CLI()
  val exitCode = cli.parse(args.toList().toTypedArray(), readStdin)
  cli.diags.forEach(Diagnostic::print)
  return cli to exitCode
}

internal fun cliCmd(commandLine: String?): Pair<CLI, ExitCodes> {
  return cli(commandLine?.split(" ")?.toTypedArray() ?: emptyArray())
}

internal fun List<ExternalDeclaration>.firstFun(): FunctionDefinition =
    first { it is FunctionDefinition } as FunctionDefinition

internal val List<Diagnostic>.ids get() = map { it.id }

internal fun Preprocessor.assertDiags(vararg ids: DiagnosticId) =
    assertEquals(ids.toList(), diags.ids)

internal fun Parser.assertDiags(vararg ids: DiagnosticId) = assertEquals(ids.toList(), diags.ids)
internal fun IDebugHandler.assertDiags(vararg ids: DiagnosticId) =
    assertEquals(ids.toList(), diags.ids)

internal fun assertPPDiagnostic(s: String, source: SourceFileName, vararg ids: DiagnosticId) {
  val diagnostics = preparePP(s, source).diags
  assertEquals(ids.toList(), diagnostics.ids)
}

internal fun <T : Any> parseSimpleTokens(it: T): LexicalToken = when (it) {
  is LexicalToken -> it
  is Punctuators -> Punctuator(it)
  is Keywords -> Keyword(it)
  is Int -> IntegralConstant(it.toString(), IntegralSuffix.NONE, Radix.DECIMAL)
  is Double -> FloatingConstant(it.toString(), FloatingSuffix.NONE, Radix.DECIMAL, null)
  is String -> StringLiteral(it, StringEncoding.CHAR)
  else -> throw IllegalArgumentException("Bad type for simple token")
}

internal fun <T : Any> Preprocessor.assertDefine(name: String, vararg replacementList: T) {
  val replList = defines[Identifier(name)]
  assertNotNull(replList, "$name is not defined")
  assertEquals(replacementList.map(::parseSimpleTokens).toList(), replList)
}

internal fun Preprocessor.assertNotDefined(name: String) {
  assert(Identifier(name) !in defines.keys)
}

internal fun <T : Any> Preprocessor.assertTokens(vararg tokens: T) =
    assertEquals(tokens.map(::parseSimpleTokens).toList(), this.tokens)

internal fun <T : LexicalToken> Preprocessor.assertTokens(tokens: List<T>) =
    assertEquals(tokens, this.tokens)
