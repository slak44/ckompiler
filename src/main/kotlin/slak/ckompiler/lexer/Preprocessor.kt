package slak.ckompiler.lexer

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.*
import kotlin.js.JsExport

typealias CLIDefines = Map<String, String>

/**
 * Handles translation phases 1 through 6, inclusive.
 *
 * C standard: 5.1.1.2
 */
@JsExport
class Preprocessor(
    sourceText: String,
    srcFileName: SourceFileName,
    targetData: MachineTargetData,
    currentDir: FSPath,
    cliDefines: CLIDefines = emptyMap(),
    initialDefines: Map<Identifier, List<LexicalToken>> = emptyMap(),
    includePaths: IncludePaths = IncludePaths.defaultPaths,
    ignoreTrigraphs: Boolean = false,
) {

  private val debugHandler: DebugHandler
  val diags get() = debugHandler.diags
  val tokens: List<LexicalToken>
  val defines: Map<Identifier, List<LexicalToken>>

  init {
    val (phase3Src, phase1Diags) = translationPhase1And2(ignoreTrigraphs, sourceText, srcFileName)
    debugHandler = DebugHandler("Preprocessor", srcFileName, phase3Src)
    debugHandler.diags += phase1Diags
    val l = Lexer(debugHandler, phase3Src, srcFileName)
    val allInitialDefines = mandatoryMacros +
        conditionalFeatureMacros +
        targetData.stddefDefines +
        targetData.stdintDefines +
        cliDefines
    val parsedCliDefines = allInitialDefines.map {
      val cliDh = DebugHandler("Preprocessor", "<command line>", it.value)
      val cliLexer = Lexer(cliDh, it.value, "<command line>")
      debugHandler.diags += cliDh.diags
      Identifier(it.key) to cliLexer.ppTokens
    }.toMap()
    val p = PPParser(
        ppTokens = l.ppTokens,
        whitespaceBefore = l.whitespaceBefore,
        initialDefines = initialDefines + parsedCliDefines,
        includePaths = includePaths,
        currentDir = currentDir,
        ignoreTrigraphs = ignoreTrigraphs,
        debugHandler = debugHandler,
        machineTargetData = targetData
    )
    defines = p.objectDefines
    tokens = p.outTokens.mapNotNull(::convert)
  }

  /**
   * First part of translation phase 7. It belongs in [slak.ckompiler.parser.Parser], but it's very
   * convenient to do it here.
   */
  private fun convert(tok: LexicalToken): LexicalToken? = when (tok) {
    is HeaderName -> logger.throwICE("HeaderName didn't disappear in phase 4") { tok }
    is NewLine -> null
    is Identifier -> Keywords.entries
        .firstOrNull { tok.name == it.keyword }
        ?.let(::Keyword)
        ?.copyDebugFrom(tok)
        ?: tok
    else -> tok
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/**
 * C standard: 6.10.8.1
 */
private val mandatoryMacros: CLIDefines = mapOf(
    "__DATE__" to "\"Mmm dd yyyy\"", // FIXME: this is dynamic
    "__FILE__" to "\"__FILE__ macro not yet implemented\"", // FIXME: this is dynamic
    "__LINE__" to "0", // FIXME: this is dynamic
    "__STDC__" to "0", // This is not a conforming implementation yet; this will remain 0 for now
    "__STDC_HOSTED__" to "1", // We support a hosted environment
    "__STDC_VERSION__" to "201112L", // Only deal with C11 for now
    "__TIME__" to "\"hh:mm:ss\"" // FIXME: this is dynamic
)

/**
 * C standard: 6.10.8.3
 */
private val conditionalFeatureMacros: CLIDefines = mapOf(
    "__STDC_NO_ATOMICS__" to "1",
    "__STDC_NO_COMPLEX__" to "1",
    "__STDC_NO_THREADS__" to "1",
    "__STDC_NO_VLA__" to "1"
)

/** @see translationPhase1And2 */
private val trigraphs = mapOf(
    "??=" to "#", "??(" to "[", "??/" to "", "??)" to "]", "??'" to "^",
    "??<" to "}", "??!" to "|", "??>" to "}", "??-" to "~", "\\\n" to ""
)

/** @see translationPhase1And2 */
private val trigraphPattern = Regex("(" +
    (trigraphs.keys - "\\\n").joinToString("|") { regexEscape(it) } + "|\\\\\n)", RegexOption.MULTILINE)

/**
 * The character set mapping is implicit via use of [String].
 *
 * Trigraph sequences are recognized and replaced; clang and gcc error on them in "gnu11"/"gnu17"
 * mode, but not in "c11"/"c17", so we will follow the standard here. However, we are still going to
 * produce warnings, and allow disabling via CLI option.
 *
 * The same regex replacement mechanism used for trigraphs is also used for joining newlines for
 * phase 2. A slash followed by a newline is erased.
 *
 * Usage of trigraphs and joined newlines will influence line/column indices in diagnostics.
 *
 * C standard: 5.1.1.2.0.1.1, 5.1.1.2.0.1.2, 5.2.1.1
 */
fun translationPhase1And2(
    ignoreTrigraphs: Boolean,
    source: String,
    srcFileName: SourceFileName,
): Pair<String, List<Diagnostic>> {
  val dh = DebugHandler("Trigraphs", srcFileName, source)

  val replaced = trigraphPattern.replace(source) {
    val replacement = trigraphs.getValue(it.groupValues[1])

    if (replacement.isNotEmpty()) dh.diagnostic {
      id = if (ignoreTrigraphs) DiagnosticId.TRIGRAPH_IGNORED else DiagnosticId.TRIGRAPH_PROCESSED
      if (!ignoreTrigraphs) formatArgs(replacement)
      columns(it.range)
    }

    replacement
  }

  return replaced to dh.diags
}
