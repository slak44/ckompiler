package slak.ckompiler.lexer

import slak.ckompiler.*
import java.io.File
import java.util.regex.Pattern

data class IncludePaths(val general: List<File>, val system: List<File>, val users: List<File>) {

  /**
   * Don't consider the current directory for includes.
   */
  var includeBarrier = false

  operator fun plus(other: IncludePaths): IncludePaths {
    val inc = IncludePaths(general + other.general, system + other.system, users + other.users)
    inc.includeBarrier = includeBarrier || other.includeBarrier
    return inc
  }

  companion object {
    val defaultPaths = IncludePaths(emptyList(), listOf(File("/usr/bin/include")), emptyList())
  }
}

class Preprocessor(sourceText: String,
                   srcFileName: SourceFileName,
                   includePaths: IncludePaths = IncludePaths.defaultPaths,
                   ignoreTrigraphs: Boolean = false) {

  private val debugHandler: DebugHandler
  val diags get() = debugHandler.diags
  val tokens: List<LexicalToken>

  init {
    val (phase3Src, phase1Diags) = translationPhase1And2(ignoreTrigraphs, sourceText, srcFileName)
    debugHandler = DebugHandler("Preprocessor", srcFileName, phase3Src)
    debugHandler.diags += phase1Diags
    val l = Lexer(debugHandler, phase3Src, srcFileName)
    val p = PPParser(l.ppTokens, includePaths, debugHandler)
    tokens = p.outTokens.mapNotNull(::convert)
    diags.forEach { it.print() }
  }

  /**
   * First part of translation phase 7.
   */
  private fun convert(tok: LexicalToken): LexicalToken? = when (tok) {
    is HeaderName -> debugHandler.logger.throwICE("HeaderName didn't disappear in phase 4") { tok }
    is NewLine -> null
    is Identifier -> {
      Keywords.values().firstOrNull { tok.name == it.keyword }
          ?.let(::Keyword)?.withStartIdx(tok.startIdx) ?: tok
    }
    else -> tok
  }
}

/**
 * FIXME: translation phases 5, and 6? should happen around here
 *
 * Translation phase 4.
 */
private class PPParser(ppTokens: List<LexicalToken>,
                       includePaths: IncludePaths,
                       debugHandler: DebugHandler) : IDebugHandler by debugHandler {

  val outTokens = mutableListOf<LexicalToken>()
  private var currentOffset = 0

  init {
    outTokens += ppTokens // FIXME
//    parse()
  }

  private tailrec fun parse() {

    return parse()
  }
}

/** @see translationPhase1And2 */
private val trigraphs = mapOf("??=" to "#", "??(" to "[", "??/" to "", "??)" to "]", "??'" to "^",
    "??<" to "}", "??!" to "|", "??>" to "}", "??-" to "~", "\\\n" to "")

/** @see translationPhase1And2 */
private fun escapeTrigraph(t: String) = "\\${t[0]}\\${t[1]}\\${t[2]}"

/** @see translationPhase1And2 */
private val trigraphPattern = Pattern.compile("(" +
    (trigraphs.keys - "\\\n").joinToString("|") { escapeTrigraph(it) } + "|\\\\\n)")

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
private fun translationPhase1And2(ignoreTrigraphs: Boolean,
                                  source: String,
                                  srcFileName: SourceFileName): Pair<String, List<Diagnostic>> {
  val dh = DebugHandler("Trigraphs", srcFileName, source)
  val matcher = trigraphPattern.matcher(source)
  val sb = StringBuilder()
  while (matcher.find()) {
    val replacement = trigraphs.getValue(matcher.group(1))
    val matchResult = matcher.toMatchResult()
    matcher.appendReplacement(sb, replacement)
    if (replacement.isNotEmpty()) dh.diagnostic {
      id = if (ignoreTrigraphs) DiagnosticId.TRIGRAPH_IGNORED else DiagnosticId.TRIGRAPH_PROCESSED
      if (!ignoreTrigraphs) formatArgs(replacement)
      columns(matchResult.start() until matchResult.end())
    }
  }
  matcher.appendTail(sb)
  return sb.toString() to dh.diags
}

/**
 * Translation phase 3.
 *
 * C standard: 5.1.1.2.0.1.3
 */
private class Lexer(debugHandler: DebugHandler, sourceText: String, srcFileName: SourceFileName) :
    IDebugHandler by debugHandler,
    ITextSourceHandler by TextSourceHandler(sourceText, srcFileName) {

  val ppTokens = mutableListOf<LexicalToken>()

  init {
    tokenize()
  }

  /** C standard: A.1.8, 6.4.0.4 */
  private fun headerName(s: String): LexicalToken? {
    // We are required by 6.4.0.4 to only recognize the `header-name` pp-token within #include or
    // #pragma directives
    val canBeHeaderName = ppTokens.size > 1 &&
        ppTokens[ppTokens.size - 2] == Punctuator(Punctuators.HASH) &&
        (ppTokens[ppTokens.size - 1] == Identifier("include") ||
            ppTokens[ppTokens.size - 1] == Identifier("pragma"))
    if (!canBeHeaderName) return null
    val quote = s[0]
    if (quote != '<' && quote != '"') return null
    val otherQuote = if (s[0] == '<') '>' else '"'
    val endIdx = s.drop(1).indexOfFirst { it == '\n' || it == otherQuote }
    if (endIdx == -1 || s[1 + endIdx] == '\n') {
      diagnostic {
        id = DiagnosticId.EXPECTED_H_Q_CHAR_SEQUENCE
        formatArgs(quote, otherQuote)
        column(if (endIdx == -1) currentOffset + s.length else currentOffset + 1 + endIdx)
      }
      return ErrorToken(if (endIdx == -1) s.length else 1 + endIdx)
    }
    return HeaderName(s.slice(1 until endIdx), quote)
  }

  /**
   * Reads and adds a single token to the [ppTokens] list.
   * Unmatched ' or " are undefined behaviour, but [characterConstant] and [stringLiteral] try to
   * deal with them nicely.
   *
   * 6.4.8 and A.1.9 syntax suggest that numbers have a more general lexical grammar and don't
   * have a type or value until translation phase 7. We don't relax the grammar, and the numbers are
   * concrete from the beginning.
   *
   * FIXME: we shouldn't print all the diagnostics encountered before translation phase 4; maybe the
   *  error was in code excluded by conditional compilation, and we aren't allowed to complain about
   *  it
   *
   * C standard: A.1.1, 6.4.0.3, 6.4.8, A.1.9
   */
  private tailrec fun tokenize() {
    dropCharsWhile {
      if (it == '\n') ppTokens += NewLine
      return@dropCharsWhile it.isWhitespace()
    }
    if (currentSrc.isEmpty()) return

    // Comments
    if (currentSrc.startsWith("//")) {
      dropCharsWhile { it != '\n' }
      return tokenize()
    } else if (currentSrc.startsWith("/*")) {
      dropCharsWhile { it != '*' || currentSrc[currentOffset + 1] != '/' }
      // Unterminated comment
      if (currentSrc.isEmpty()) diagnostic {
        id = DiagnosticId.UNFINISHED_COMMENT
        column(currentOffset)
      } else {
        // Get rid of the '*/'
        dropChars(2)
      }
      return tokenize()
    }

    // Ordering to avoid conflicts between token prefixes:
    // headerName before punct
    // headerName before stringLiteral
    // characterConstant before identifier
    // stringLiteral before identifier
    // floatingConstant before integerConstant
    // floatingConstant before punct

    val token =
        headerName(currentSrc) ?: characterConstant(currentSrc, currentOffset)
        ?: stringLiteral(currentSrc, currentOffset) ?: floatingConstant(currentSrc, currentOffset)
        ?: integerConstant(currentSrc, currentOffset) ?: identifier(currentSrc) ?: punct(currentSrc)
        ?: logger.throwICE("Extraneous character")

    if (token is CharLiteral && token.data.isEmpty()) diagnostic {
      id = DiagnosticId.EMPTY_CHAR_CONSTANT
      columns(currentOffset..(currentOffset + 1))
    }
    token.startIdx = currentOffset
    ppTokens += token
    dropChars(token.consumedChars)
    return tokenize()
  }
}