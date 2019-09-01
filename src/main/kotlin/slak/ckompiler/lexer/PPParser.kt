package slak.ckompiler.lexer

import slak.ckompiler.*
import slak.ckompiler.parser.*
import java.io.File

typealias ParsedObjectDefines = Map<Identifier, List<LexicalToken>>

/**
 * FIXME: translation phases 5, and 6? should happen around here
 *
 * Translation phase 4.
 */
class PPParser(
    ppTokens: List<LexicalToken>,
    private val whitespaceBefore: List<String>,
    initialDefines: ParsedObjectDefines,
    private val includePaths: IncludePaths,
    private val currentDir: File,
    private val ignoreTrigraphs: Boolean,
    private val debugHandler: DebugHandler
) : IDebugHandler by debugHandler, ITokenHandler by TokenHandler(ppTokens, debugHandler) {

  val outTokens = mutableListOf<LexicalToken>()
  val objectDefines = mutableMapOf<Identifier, List<LexicalToken>>()

  init {
    objectDefines += initialDefines
    parseLine()
  }

  /**
   * Returns null if this is not an `if-group`, or the condition tokens + the directive name if it
   * is.
   */
  private fun ifGroup(): Pair<List<LexicalToken>, String>? {
    // This check ensures we can call relative(1) below
    if (tokensLeft < 2) return null
    val groupKind = relative(1) as? Identifier ?: return null
    if (groupKind.name !in listOf("ifdef", "ifndef", "if")) return null
    eat() // The #
    eat() // The group kind
    val tentativeMissingNameIdx = colPastTheEnd(-1)
    val newlineIdx = indexOfFirst { it == NewLine }
    val lineEndIdx = if (newlineIdx == -1) tokenCount else newlineIdx
    return tokenContext(lineEndIdx) {
      if (isEaten()) {
        diagnostic {
          id = DiagnosticId.MACRO_NAME_MISSING
          column(tentativeMissingNameIdx)
        }
        return@tokenContext emptyList<LexicalToken>()
      }
      // Constant expression condition, return everything
      if (groupKind.name == "if") {
        eatUntil(it.size)
        return@tokenContext it
      }
      // Identifier condition
      val ident = current()
      eat()
      if (ident !is Identifier) {
        diagnostic {
          id = DiagnosticId.MACRO_NAME_NOT_IDENT
          errorOn(ident)
        }
        // Eat the rest of the directive and return, so we don't print the warning below after this
        eatUntil(it.size)
        return@tokenContext emptyList<LexicalToken>()
      }
      if (isNotEaten()) {
        diagnostic {
          id = DiagnosticId.EXTRA_TOKENS_DIRECTIVE
          formatArgs("#${groupKind.name}")
          errorOn(safeToken(0)..it.last())
        }
        eatUntil(it.size)
      }
      // If these synthetic idents are used in a diagnostic, the startIdx must be valid
      // Also, if a diagnostic does something like defd..otherToken, it will work correctly, even
      // though "defined" may be longer than the otherToken
      val defd = Identifier("defined").copyDebugFrom(ident)
      val unaryNot = Punctuator(Punctuators.NOT).copyDebugFrom(ident)
      return@tokenContext listOfNotNull(
          if (groupKind.name == "ifndef") unaryNot else null, defd, ident)
    } to groupKind.name
  }

  /**
   * Deals with the unary `defined` operator in preprocessing expressions. Returns null if it was
   * used incorrectly, or a token representing whether the target identifier was defined or not.
   * The second item of the return pair counts how many tokens should be eaten from the source.
   */
  private fun handleDefinedOperator(idx: Int, e: List<LexicalToken>): Pair<LexicalToken?, Int> {
    fun definedIdent(ident: Identifier): LexicalToken {
      val isDefined = objectDefines[ident] != null
      val ct = if (isDefined) IntegralConstant.one() else IntegralConstant.zero()
      return ct.copyDebugFrom(ident)
    }

    if (idx + 1 >= e.size) {
      diagnostic {
        id = DiagnosticId.EXPECTED_IDENT
        column(e.last().range.last)
      }
      return null to 1
    }
    val firstTok = e[idx + 1]
    when {
      firstTok.asPunct() == Punctuators.LPAREN -> {
        if (idx + 2 >= e.size) {
          diagnostic {
            id = DiagnosticId.EXPECTED_IDENT
            column(e.last().range.last)
          }
          return null to 2
        }
        val ident = e[idx + 2] as? Identifier
        if (ident == null) {
          diagnostic {
            id = DiagnosticId.EXPECTED_IDENT
            errorOn(e[idx + 2])
          }
          return null to 3
        }
        if (idx + 3 >= e.size || e[idx + 3].asPunct() != Punctuators.RPAREN) {
          diagnostic {
            id = DiagnosticId.UNMATCHED_PAREN
            formatArgs(")")
            if (idx + 3 >= e.size) {
              // No token for paren
              column(e.last().range.last + 1)
            } else {
              // Wrong token as paren
              errorOn(e[idx + 3])
            }
          }
          diagnostic {
            id = DiagnosticId.MATCH_PAREN_TARGET
            formatArgs("(")
            errorOn(firstTok)
          }
          return null to 4
        }
        return definedIdent(ident) to 4
      }
      firstTok is Identifier -> return definedIdent(firstTok) to 2
      else -> {
        diagnostic {
          id = DiagnosticId.EXPECTED_IDENT
          column(e.last().range.last)
        }
        return null to 2
      }
    }
  }

  private fun evaluateConstExpr(e: List<LexicalToken>, directiveName: String): Boolean {
    val processedToks = mutableListOf<LexicalToken>()
    var idx = 0
    while (idx < e.size) {
      val it = e[idx]
      if (it is Identifier && it.name == "defined") {
        val (tok, count) = handleDefinedOperator(idx, e)
        tok ?: return false
        idx += count
        processedToks += tok
        continue
      }
      processedToks += it
      idx++
    }
    val replaced = doMacroReplacement(processedToks).map {
      if (it is Identifier) {
        diagnostic {
          id = DiagnosticId.NOT_DEFINED_IS_0
          formatArgs(it.name)
          errorOn(it)
        }
        IntegralConstant.zero().copyDebugFrom(it)
      } else {
        it
      }
    }
    val pm = ParenMatcher(this, TokenHandler(replaced, this))
    val p = ConstantExprParser(ConstantExprType.PREPROCESSOR, pm, ConstExprIdents, ConstExprIdents)
    val it = p.parseConstant(replaced.size)
    if (p.isNotEaten()) diagnostic {
      id = DiagnosticId.EXTRA_TOKENS_DIRECTIVE
      formatArgs("#$directiveName")
      errorOn(p.current()..p.tokenAt(p.tokenCount - 1))
    }
    return when (it) {
      // If the condition is missing, we simply return false
      null -> false
      // If the condition has an error, a diag was generated for sure, so we can presume anything
      is ErrorExpression -> false
      is IntegerConstantNode -> it.value != 0L
      is CharacterConstantNode -> it.char != 0
      is FloatingConstantNode, is VoidExpression -> {
        logger.throwICE("Not a valid constant from ConstantExprParser with PREPROCESSOR type")
      }
      is StringLiteralNode -> logger.throwICE("Not a valid constant from ConstantExprParser")
    }
  }

  /**
   * Returns the elif's condition tokens.
   */
  private fun elifGroup(elif: Identifier): List<LexicalToken> {
    if (isNotEaten() && current() == NewLine) {
      diagnostic {
        id = DiagnosticId.ELIF_NO_CONDITION
        errorOn(elif)
      }
      return emptyList()
    }
    val firstNewLine = indexOfFirst { it == NewLine }
    val lineEnd = if (firstNewLine == -1) tokenCount else firstNewLine
    return tokenContext(lineEnd) {
      eatUntil(it.size)
      it
    }
  }

  /**
   * Parses `if-section`. Returns null if there is something other than that. Unlike other
   * directives below, this also parses the "#" and is not limited to one line.
   */
  private fun ifSection(): Pair<List<LexicalToken>, ParsedObjectDefines>? {
    if (current().asPunct() != Punctuators.HASH) return null
    if (isEaten()) return null
    val groupStart = safeToken(0)
    val (ifGroupCond, directiveName) = ifGroup() ?: return null
    eat() // if-group's newline
    var ifSectionStack = 1 // Counts how many if-groups were found
    var lastCondResult = evaluateConstExpr(ifGroupCond, directiveName)
    var pickedGroup: Pair<List<LexicalToken>, List<String>>? = null
    var wasElseFound = false
    var toks = mutableListOf<LexicalToken>()
    var tokWhitespace = mutableListOf<String>()

    // This is here to reduce indent level inside
    fun ifDirectives(ident: Identifier): Pair<List<LexicalToken>, ParsedObjectDefines>? {
      eat() // #
      eat() // ident
      if (lastCondResult && pickedGroup == null) {
        pickedGroup = toks to tokWhitespace
      }
      if ((ident.name == "elif" || ident.name == "else") && wasElseFound) {
        diagnostic {
          id = DiagnosticId.ELSE_NOT_LAST
          formatArgs("#${ident.name}")
          errorOn(ident)
        }
      }
      if (ident.name == "elif") {
        lastCondResult = evaluateConstExpr(elifGroup(ident), "elif")
      }
      if ((ident.name == "else" || ident.name == "endif") && isNotEaten() && current() != NewLine) {
        val firstNewLine = indexOfFirst { it == NewLine }
        diagnostic {
          id = DiagnosticId.EXTRA_TOKENS_DIRECTIVE
          formatArgs("#${ident.name}")
          val lastLineTokIdx = if (firstNewLine == -1) tokenCount - 1 else firstNewLine - 1
          errorOn(safeToken(0)..tokenAt(lastLineTokIdx))
        }
        if (firstNewLine == -1) eatUntil(tokenCount)
        else eatUntil(firstNewLine)
      }
      if (ident.name == "else") {
        // This ensures that the else group will be picked if nothing else was
        lastCondResult = true
        wasElseFound = true
      }
      if (ident.name == "endif") {
        if (pickedGroup == null) return emptyList<LexicalToken>() to emptyMap()
        // End of if-section; exit function with correct tokens, or with nothing (if the
        // condition was false and there are no elif/else groups)
        val recursiveParser = PPParser(
            ppTokens = pickedGroup!!.first,
            whitespaceBefore = pickedGroup!!.second,
            initialDefines = objectDefines,
            includePaths = includePaths,
            currentDir = currentDir,
            ignoreTrigraphs = ignoreTrigraphs,
            debugHandler = debugHandler
        )
        return recursiveParser.outTokens to recursiveParser.objectDefines
      }
      toks = mutableListOf()
      tokWhitespace = mutableListOf()
      return null
    }

    outerLoop@ while (true) {
      while (isNotEaten() && current() == NewLine) {
        toks.add(current())
        tokWhitespace.add(whitespaceBefore[currentIdx])
        eat()
      }
      if (isEaten()) break
      val possibleHash = current()
      if (possibleHash.asPunct() != Punctuators.HASH) {
        toks.add(possibleHash)
        tokWhitespace.add(whitespaceBefore[currentIdx])
        eat()
        continue
      }
      // Make sure relative(1) below doesn't crash
      if (tokensLeft < 2) {
        toks.add(possibleHash)
        tokWhitespace.add(whitespaceBefore[currentIdx])
        eat()
        break
      }
      val ident = relative(1) as? Identifier
      if (ident == null) {
        toks.add(possibleHash)
        toks.add(relative(1))
        tokWhitespace.add(whitespaceBefore[currentIdx])
        tokWhitespace.add(whitespaceBefore[currentIdx + 1])
        eat()
        eat()
        continue
      }
      if (ident.name in listOf("if", "ifdef", "ifndef")) {
        ifSectionStack++
      }
      if (ifSectionStack == 1 && ident.name in listOf("elif", "else", "endif")) {
        val maybeReturn = ifDirectives(ident)
        if (maybeReturn != null) return maybeReturn
      }
      if (ident.name == "endif") {
        ifSectionStack--
        if (ifSectionStack < 1) {
          // This technically means there are extra #endifs
          // If this group is passed to the recursive parser, it will notice them and emit diags
          // We just pretend they weren't here
          ifSectionStack = 1
        }
      }
      if (isEaten()) break
      toks.add(current())
      tokWhitespace.add(whitespaceBefore[currentIdx])
      eat()
    }
    if (ifSectionStack > 0) diagnostic {
      id = DiagnosticId.UNTERMINATED_CONDITIONAL
      errorOn(groupStart)
    } else {
      logger.throwICE("Impossible case; should have returned out of this function") {
        "ifSectionStack: $ifSectionStack"
      }
    }
    return emptyList<LexicalToken>() to emptyMap()
  }

  private fun extraTokensOnLineDiag(directiveName: String) {
    if (isNotEaten()) {
      diagnostic {
        id = DiagnosticId.EXTRA_TOKENS_DIRECTIVE
        errorOn(safeToken(0)..safeToken(tokenCount))
        formatArgs("#$directiveName")
      }
      eatUntil(tokenCount)
    }
  }

  private fun addDefineObjectMacro(definedIdent: Identifier, replacementList: List<LexicalToken>) {
    if (definedIdent in objectDefines && replacementList != objectDefines[definedIdent]) {
      diagnostic {
        id = DiagnosticId.MACRO_REDEFINITION
        formatArgs(definedIdent.name)
        errorOn(definedIdent)
      }
      diagnostic {
        id = DiagnosticId.REDEFINITION_PREVIOUS
        errorOn(objectDefines.keys.first { (name) -> name == definedIdent.name })
      }
    }
    objectDefines[definedIdent] = replacementList
  }

  private fun doMacroReplacement(ident: Identifier): List<LexicalToken>? {
    val replacementList = objectDefines[ident] ?: return null
    val shouldRecurse = replacementList.any { it is Identifier && it in objectDefines }
    return if (shouldRecurse) doMacroReplacement(replacementList) else replacementList
  }

  /**
   * Does macro-replacement on the given token list. No-op if no macro names found. Returns a
   * recursively-macro-replaced token list.
   *
   * FIXME: handle function-like macros
   */
  private fun doMacroReplacement(toks: List<LexicalToken>): List<LexicalToken> {
    val res = mutableListOf<LexicalToken>()
    for (tok in toks) {
      if (tok is Identifier) {
        res += doMacroReplacement(tok)?.map {
          val src = it.cloneSource()
          it.copyDebugFrom(tok)
          it.expandedName = tok.name
          it.expandedFrom = src
          it
        } ?: listOf(tok)
      } else {
        res += tok
      }
    }
    return res
  }

  private fun processInclude(header: HeaderName) {
    val includedFile = includePaths.search(header.data, currentDir, header.kind == '<')
    if (includedFile == null) {
      diagnostic {
        id = DiagnosticId.FILE_NOT_FOUND
        formatArgs(header.data)
        errorOn(header)
      }
      return
    }
    // Pass it through phases 1-4
    val (phase3Src, phase1Diags) =
        translationPhase1And2(ignoreTrigraphs, includedFile.readText(), includedFile.absolutePath)
    val recursiveDH = DebugHandler("Preprocessor", includedFile.absolutePath, phase3Src)
    recursiveDH.diags += phase1Diags
    val l = Lexer(recursiveDH, phase3Src, includedFile.absolutePath)
    val p = PPParser(
        ppTokens = l.ppTokens,
        whitespaceBefore = l.whitespaceBefore,
        initialDefines = objectDefines,
        includePaths = includePaths,
        currentDir = includedFile.parentFile,
        ignoreTrigraphs = ignoreTrigraphs,
        debugHandler = recursiveDH
    )
    debugHandler.diags += recursiveDH.diags
    objectDefines.clear()
    objectDefines += p.objectDefines
    outTokens += p.outTokens
  }

  /**
   * Parses #include directives that require macro replacement.
   */
  private fun macroReplacedInclude(): HeaderName? = tokenContext(tokenCount) { afterInclude ->
    val replacedName = doMacroReplacement(afterInclude)
    val targetText = replacedName.joinToString("") {
      if (it.expandedFrom == null) it.sourceText!!.slice(it.range)
      else it.expandedFrom!!.sourceText!!.slice(it.expandedFrom!!.range)
    }
    val actualHeaderName = headerName(targetText)
    if (actualHeaderName == null) {
      diagnostic {
        id = DiagnosticId.EXPECTED_HEADER_NAME
        errorOn(current())
      }
      return@tokenContext null
    }
    if (actualHeaderName !is HeaderName) {
      diagnostic {
        id = DiagnosticId.EXPECTED_H_Q_CHAR_SEQUENCE
        formatArgs(targetText[0], if (targetText[0] == '<') '>' else '"')
        errorOn(current())
      }
      return@tokenContext null
    }
    if (targetText.length != actualHeaderName.consumedChars) diagnostic {
      id = DiagnosticId.EXTRA_TOKENS_DIRECTIVE
      formatArgs("#include")
      val strLeft = targetText.drop(actualHeaderName.consumedChars)
      columns(current().startIdx until (current().startIdx + strLeft.length))
    }
    return@tokenContext actualHeaderName
  }

  /**
   * Parsing #include directives.
   *
   * C standard: 6.10.2
   */
  private fun include(): Boolean {
    val tok = current()
    if (tok !is Identifier || tok.name != "include") return false
    eat() // Eat "include"
    if (current() is HeaderName) {
      val header = current() as HeaderName
      eat()
      extraTokensOnLineDiag("include")
      processInclude(header)
    } else {
      val header = macroReplacedInclude()
      eatUntil(tokenCount)
      if (header != null) processInclude(header)
    }
    return true
  }

  /**
   * Macro definitions.
   *
   * FIXME: implement function macros
   *
   * C standard: 6.10.3
   */
  private fun define(): Boolean {
    val tok = current()
    if (tok !is Identifier || tok.name != "define") return false
    eat() // Eat "define"
    if (isEaten()) {
      diagnostic {
        id = DiagnosticId.MACRO_NAME_MISSING
        column(colPastTheEnd(0))
      }
      return true
    }
    val definedIdent = current() as? Identifier
    if (definedIdent == null) {
      diagnostic {
        id = DiagnosticId.MACRO_NAME_NOT_IDENT
        errorOn(safeToken(0))
      }
      eatUntil(tokenCount)
      return true
    }
    eat() // The definedIdent
    if (isNotEaten() &&
        current().asPunct() == Punctuators.LPAREN &&
        whitespaceBefore[currentIdx].isEmpty()) {
      TODO("function-y macros aren't implemented yet")
    }
    // Everything else until the newline is part of the `replacement-list`
    // If there is nothing left, the macro has no replacement list (valid case)
    tokenContext(tokenCount) {
      addDefineObjectMacro(definedIdent, it)
      eatUntil(tokenCount)
    }
    return true
  }

  /**
   * FIXME: undef directive
   *
   * FIXME: handle scope of macro defines
   */
  private fun undef(): Boolean {
    return false
  }

  /**
   * FIXME: line directive
   */
  private fun line(): Boolean {
    return false
  }

  /**
   * Error directives. Technically the things in it have to be valid pp-tokens, but like clang and
   * gcc we accept any text between "error" and the newline.
   *
   * FIXME: swallow diagnostics from the tokens in this directive
   */
  private fun error(): Boolean {
    val tok = current()
    if (tok !is Identifier || tok.name != "error") return false
    eat() // Eat "error"
    diagnostic {
      id = DiagnosticId.PP_ERROR_DIRECTIVE
      if (isNotEaten()) {
        val srcRange = safeToken(0)..safeToken(tokenCount)
        formatArgs(sourceText.substring(srcRange.range))
        errorOn(tok..srcRange)
      } else {
        formatArgs("")
        errorOn(tok)
      }
    }
    return true
  }

  /**
   * FIXME: pragma directives
   */
  private fun pragma(): Boolean {
    return false
  }

  private fun invalidIfSectionDirectives(): Boolean {
    val ident = current() as? Identifier
    if (ident != null && ident.name in listOf("elif", "else", "endif")) {
      diagnostic {
        id = DiagnosticId.DIRECTIVE_WITHOUT_IF
        formatArgs("#${ident.name}")
        errorOn(ident)
      }
      eat() // The ident
      extraTokensOnLineDiag(ident.name)
      return true
    }
    return false
  }

  private fun nonDirective(): Boolean {
    diagnostic {
      id = DiagnosticId.INVALID_PP_DIRECTIVE
      val tok = safeToken(0)
      formatArgs(sourceText.substring(tok.range))
      errorOn(tok)
    }
    return true
  }

  private tailrec fun parseLine() {
    // We aren't interested in leading newlines
    while (isNotEaten() && current() == NewLine) eat()
    if (isEaten()) return
    val condResult = ifSection()
    if (condResult != null) {
      outTokens += condResult.first
      objectDefines += condResult.second
      return parseLine()
    }
    val newlineIdx = indexOfFirst { it == NewLine }
    val lineEndIdx = if (newlineIdx == -1) tokenCount else newlineIdx
    tokenContext(lineEndIdx) {
      // `text-line` in the standard
      if (current().asPunct() != Punctuators.HASH) {
        outTokens += doMacroReplacement(it)
        eatUntil(lineEndIdx)
        return@tokenContext
      }
      eat() // The #
      if (isEaten()) return@tokenContext // Null directive case
      // Try each one in sequence
      include() || define() || undef() || line() || error() || pragma() ||
          invalidIfSectionDirectives() || nonDirective()
    }
    eat() // Get rid of the newline too
    return parseLine()
  }
}
