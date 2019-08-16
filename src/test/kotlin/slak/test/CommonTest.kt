package slak.test

import slak.ckompiler.*
import slak.ckompiler.analysis.CFG
import slak.ckompiler.lexer.*
import slak.ckompiler.parser.*
import java.io.File
import kotlin.test.assertEquals

internal fun Preprocessor.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), diags)
internal fun Parser.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal fun IDebugHandler.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal val <T : Any> T.source get() = "<test/${javaClass.simpleName}>"
internal fun <T : Any> T.resource(s: String) = File(javaClass.classLoader.getResource(s)!!.file)

internal fun preparePP(s: String, source: SourceFileName): Preprocessor {
  val incs = IncludePaths(emptyList(),
      listOf(IncludePaths.resource("headers/system")),
      listOf(IncludePaths.resource("headers/users")))
  return Preprocessor(
      sourceText = s,
      srcFileName = source,
      includePaths = incs + IncludePaths.defaultPaths,
      currentDir = File(".")
  )
}

internal fun prepareCode(s: String, source: SourceFileName): Parser {
  val pp = preparePP(s, source)
  pp.assertNoDiagnostics()
  return Parser(pp.tokens, source, s)
}

internal fun prepareCFG(s: String, source: SourceFileName): CFG {
  val p = prepareCode(s, source)
  p.assertNoDiagnostics()
  return CFG(p.root.decls.firstFun(), source, s)
}

internal fun prepareCFG(file: File, source: SourceFileName): CFG {
  return prepareCFG(file.readText(), source)
}

@JvmName("cli_array")
internal fun cli(args: Array<String>): Pair<CLI, ExitCodes> {
  val cli = CLI()
  val exitCode = cli.parse(args)
  cli.diags.forEach(Diagnostic::print)
  return cli to exitCode
}

@JvmName("cli_varag")
internal fun cli(vararg args: String): Pair<CLI, ExitCodes> {
  return cli(args.toList().toTypedArray())
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

internal fun <T : Any> Preprocessor.assertTokens(vararg tokens: T) =
    assertEquals(tokens.map(::parseSimpleTokens).toList(), this.tokens)

internal fun <T : LexicalToken> Preprocessor.assertTokens(tokens: List<T>) =
    assertEquals(tokens, this.tokens)
