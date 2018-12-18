package slak.ckompiler.parser

import mu.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.lexer.*
import java.util.*
import kotlin.math.min

/**
 * Parses a translation unit.
 *
 * C standard: A.2.4, 6.9
 *
 * @param tokens list of tokens to parse
 * @param srcFileName the name of the file in which the tokens were extracted from
 */
class Parser(tokens: List<Token>,
             private val srcFileName: SourceFileName,
             private val srcText: String) {
  private val tokStack = Stack<List<Token>>()
  private val idxStack = Stack<Int>()
  private val scopeStack = Stack<LexicalScope>()
  val diags = mutableListOf<Diagnostic>()
  val root = RootNode()

  companion object {
    private val logger = KotlinLogging.logger("Parser")
    private val storageClassSpecifier =
        listOf(Keywords.EXTERN, Keywords.STATIC, Keywords.AUTO, Keywords.REGISTER)
    private val typeSpecifier = listOf(Keywords.VOID, Keywords.CHAR, Keywords.SHORT, Keywords.INT,
        Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE, Keywords.SIGNED, Keywords.UNSIGNED,
        Keywords.BOOL, Keywords.COMPLEX)
    private val typeQualifier =
        listOf(Keywords.CONST, Keywords.RESTRICT, Keywords.VOLATILE, Keywords.ATOMIC)
    private val funSpecifier = listOf(Keywords.NORETURN, Keywords.INLINE)
  }

  init {
    tokStack.push(tokens)
    idxStack.push(0)
    scopeStack.push(LexicalScope())
    translationUnit()
    diags.forEach { it.print() }
  }

  /**
   * Create a [LexicalScope], and run the given [block] within that scope.
   * @param block called with the receiver parameter as the created scope
   * @return the value returned by [block]
   */
  private fun <R> scoped(block: LexicalScope.() -> R): R = LexicalScope().withScope(block)

  /**
   * Run the given [block] within the receiver scope.
   * @param block called with its receiver parameter as the original scope
   * @return the value returned by [block]
   */
  private fun <R> LexicalScope.withScope(block: LexicalScope.() -> R): R {
    scopeStack.push(this)
    val ret = this.block()
    scopeStack.pop()
    return ret
  }

  /**
   * Adds a new identifier to the current scope. If the identifier already was in the current scope,
   * it is not added again, and diagnostics are printed.
   */
  private fun newIdentifier(id: IdentifierNode, isLabel: Boolean = false) {
    val listRef = if (isLabel) scopeStack.peek().labels else scopeStack.peek().idents
    // FIXME: we really should know more data about these idents, like symbol type (func/var)
    val foundId = listRef.firstOrNull { it.name == id.name }
    // FIXME: this can be used to maybe give a diagnostic about name shadowing
    // val isShadowed = scopeStack.peek().idents.any { it.name == id.name }
    if (foundId != null) {
      parserDiagnostic {
        this.id = if (isLabel) DiagnosticId.REDEFINITION_LABEL else DiagnosticId.REDEFINITION
        formatArgs(id.name)
        // FIXME: location of id
      }
      parserDiagnostic {
        this.id = DiagnosticId.REDEFINITION_PREVIOUS
        // FIXME: location of foundId
      }
      return
    }
    listRef.add(id)
  }

  /**
   * Searches all the scopes for a given identifier.
   * @return null if no such identifier exists, or the previous [IdentifierNode] otherwise
   */
  private fun searchInScope(target: IdentifierNode): IdentifierNode? {
    scopeStack.forEach {
      val idx = it.idents.indexOfFirst { (name) -> name == target.name }
      if (idx != -1) return it.idents[idx]
    }
    return null
  }

  /** Gets a token, or if all were eaten, the last one. Useful for diagnostics. */
  private fun safeToken(offset: Int) =
      if (isEaten()) tokStack.peek().last()
      else tokStack.peek()[min(idxStack.peek() + offset, tokStack.peek().size - 1)]

  /** Get the column just after the end of the token at [offset]. Useful for diagnostics. */
  private fun colPastTheEnd(offset: Int): Int {
    val tok = safeToken(offset)
    return tok.startIdx + tok.consumedChars
  }

  /**
   * Creates a "sub-parser" context for a given list of tokens. However many elements are eaten in
   * the sub context will be eaten in the parent context too. Useful for parsing parenthesis and the
   * like.
   *
   * The list of tokens starts at the current index (inclusive), and ends at the
   * given [endIdx] (exclusive).
   */
  private fun <T> tokenContext(endIdx: Int, block: (List<Token>) -> T): T {
    val tokens = takeUntil(endIdx)
    tokStack.push(tokens)
    idxStack.push(0)
    val result = block(tokens)
    tokStack.pop()
    val eatenInContext = idxStack.pop()
    eatList(eatenInContext)
    return result
  }

  /** @return the first (real) index matching the condition, or -1 if there is none */
  private fun indexOfFirst(block: (Token) -> Boolean): Int {
    val idx = tokStack.peek().drop(idxStack.peek()).indexOfFirst(block)
    return if (idx == -1) -1 else idx + idxStack.peek()
  }

  /**
   * Get the tokens until the given index.
   * Eats nothing.
   * @param endIdx the (real) idx of the sublist end (exclusive)
   */
  private fun takeUntil(endIdx: Int): List<Token> = tokStack.peek().subList(idxStack.peek(), endIdx)

  private fun isEaten(): Boolean = idxStack.peek() >= tokStack.peek().size

  private fun current(): Token = tokStack.peek()[idxStack.peek()]

  private fun lookahead(): Token = tokStack.peek()[idxStack.peek() + 1]

  private fun eat() = eatList(1)

  private fun eatList(length: Int) {
    idxStack.push(idxStack.pop() + length)
  }

  /**
   * Eats tokens unconditionally until a semicolon or the end of the token list.
   * Does not eat the semicolon.
   */
  private fun eatToSemi() {
    while (!isEaten() && current().asPunct() != Punctuators.SEMICOLON) eat()
  }

  private fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags.add(createDiagnostic {
      sourceFileName = srcFileName
      sourceText = srcText
      origin = "Parser"
      this.build()
    })
  }

  /**
   * Parses an expression.
   * C standard: A.2.1
   * @return null if there is no expression, the [Expression] otherwise
   */
  private fun parseExpr(endIdx: Int): Expression? = tokenContext(endIdx) {
    val primary: Expression = parsePrimaryExpr().let { expr ->
      if (expr != null) return@let expr
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_PRIMARY
        errorOn(safeToken(1))
      }
      return@tokenContext null
    }
    return@tokenContext parseExprImpl(primary, 0)
  }

  private fun parseExprImpl(lhsInit: Expression, minPrecedence: Int): Expression {
    var lhs = lhsInit
    while (true) {
      if (isEaten()) break
      val op = current().asBinaryOperator() ?: break
      if (op.precedence < minPrecedence) break
      eat()
      var rhs: Expression = parsePrimaryExpr().let {
        if (it != null) return@let it
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_PRIMARY
          errorOn(safeToken(0))
        }
        return@parseExprImpl ErrorExpression()
      }
      while (true) {
        if (isEaten()) break
        val innerOp = current().asBinaryOperator() ?: break
        if (innerOp.precedence <= op.precedence &&
            !(innerOp.assoc == Associativity.RIGHT_TO_LEFT &&
                innerOp.precedence == op.precedence)) {
          break
        }
        rhs = parseExprImpl(rhs, innerOp.precedence)
      }
      lhs = BinaryExpression(op, lhs, rhs)
    }
    return lhs
  }

  private fun parseBaseExpr(): Expression? = when {
    // FIXME: implement generic-selection (A.2.1/6.5.1.1)
    current().asPunct() == Punctuators.RPAREN -> {
      // This usually happens when there are unmatched parens
      eat()
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      ErrorExpression()
    }
    current().asPunct() == Punctuators.LPAREN -> {
      if (lookahead().asPunct() == Punctuators.RPAREN) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_EXPR
          errorOn(safeToken(1))
        }
        ErrorExpression()
      } else {
        val endParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (endParenIdx == -1) {
          eatToSemi()
          ErrorExpression()
        } else {
          eat() // Get rid of the LPAREN
          val expr = parseExpr(endParenIdx)
          eat() // Get rid of the RPAREN
          expr
        }
      }
    }
    else -> parseTerminal()?.let {
      eat()
      it
    }
  }

  private fun parseArgumentExprList(): List<Expression> {
    val funcArgs = mutableListOf<Expression>()
    val callEnd = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    eat() // The '('
    if (callEnd == -1) {
      TODO("error where function call has unmatched paren")
    }
    if (current().asPunct() == Punctuators.RPAREN) {
      eat() // The ')'
      // No parameters; this is not an error case
      return emptyList()
    }
    tokenContext(callEnd) {
      while (!isEaten()) {
        // The arguments can have parens with commas in them
        // We're interested in the comma that comes after the argument expression
        // So balance the parens, and look for the first comma after them
        // Also, we do not eat what we find; we're only searching for the end of the current arg
        // Once found, parseExpr handles parsing the arg and eating it
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched" +
              "paren in the argument-expression-list")
        }
        val commaIdx = indexOfFirst { c -> c == Punctuators.COMMA }
        val arg = parseExpr(if (commaIdx == -1) it.size else commaIdx)
            ?: TODO("handle error case with a null (error'd) expr")
        funcArgs.add(arg)
        if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
          // Expected case; found comma that separates args
          eat()
        }
      }
    }
    eat() // The ')'
    return funcArgs
  }

  private fun parsePostfixExpression(): Expression? {
    // FIXME: implement initializer-lists (6.5.2)
    val expr = parseBaseExpr()
    return when {
      isEaten() || expr == null -> return expr
      current().asPunct() == Punctuators.LSQPAREN -> {
        TODO("implement subscript operator")
      }
      current().asPunct() == Punctuators.LPAREN -> {
        return FunctionCall(expr, parseArgumentExprList())
      }
      current().asPunct() == Punctuators.DOT -> {
        TODO("implement direct struct/union access operator")
      }
      current().asPunct() == Punctuators.ARROW -> {
        TODO("implement indirect struct/union access operator")
      }
      current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
        val c = current().asPunct()
        eat() // The postfix op
        if (c == Punctuators.INC) PostfixIncrement(expr)
        else PostfixDecrement(expr)
      }
      else -> return expr
    }
  }

  private fun parseUnaryExpression(): Expression? = when {
    current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
      val c = current().asPunct()
      eat() // The prefix op
      val expr = parseUnaryExpression() ?: ErrorExpression()
      if (c == Punctuators.INC) PrefixIncrement(expr)
      else PrefixDecrement(expr)
    }
    current().asUnaryOperator() != null -> {
      val c = current().asUnaryOperator()!!
      eat() // The unary op
      val expr = parsePrimaryExpr() ?: ErrorExpression()
      UnaryExpression(c, expr)
    }
    current().asKeyword() == Keywords.ALIGNOF -> {
      eat() // The ALIGNOF
      if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
        TODO("throw some error here; the standard wants parens for this")
      } else {
        TODO("implement `_Alignof ( type-name )` expressions")
      }
    }
    current().asKeyword() == Keywords.SIZEOF -> {
      eat() // The SIZEOF
      when {
        isEaten() -> ErrorExpression()
        current().asPunct() == Punctuators.LPAREN -> {
          TODO("implement `sizeof ( type-name )` expressions")
        }
        else -> SizeofExpression(parseUnaryExpression() ?: ErrorExpression())
      }
    }
    else -> parsePostfixExpression()
  }

  /**
   * Looks for a primary expression. Eats what it finds.
   *
   * As a note, this function does not parse what the standard calls "primary-expression" (that
   * would be [parseBaseExpr]); it parses "cast-expression".
   *
   * C standard: A.2.1, 6.4.4, 6.5.3
   * @see parseTerminal
   * @return null if no primary was found, or the [Expression] otherwise
   */
  private fun parsePrimaryExpr(): Expression? {
    if (isEaten()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        if (tokStack.peek().isNotEmpty()) errorOn(safeToken(0))
        else errorOn(tokStack[tokStack.size - 2][idxStack[idxStack.size - 2]])
      }
      return ErrorExpression()
    }
    // FIXME: here we can also have a cast, that needs to be differentiated from `( expression )`
    return parseUnaryExpression()
  }

  /**
   * All terminals are one token long. Does not eat anything.
   * C standard: A.2.1, 6.5.1, 6.4.4.4
   * @see CharacterConstantNode
   * @return the [Terminal] node, or null if no terminal was found
   */
  private fun parseTerminal(): Expression? {
    val tok = current()
    when (tok) {
      is Identifier -> return IdentifierNode(tok.name)
      is IntegralConstant -> {
        // FIXME conversions might fail here?
        return IntegerConstantNode(tok.n.toLong(tok.radix.toInt()), tok.suffix)
      }
      is FloatingConstant -> {
        // FIXME conversions might fail here?
        return FloatingConstantNode(tok.f.toDouble(), tok.suffix)
      }
      // FIXME handle enum constants
      is CharLiteral -> {
        val char = if (tok.data.isNotEmpty()) {
          tok.data[0].toInt()
        } else {
          parserDiagnostic {
            id = DiagnosticId.EMPTY_CHAR_CONSTANT
            errorOn(safeToken(0))
          }
          0
        }
        return CharacterConstantNode(char, tok.encoding)
      }
      is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding)
      else -> return null
    }
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDeclSpecifiers(): DeclarationSpecifier {
    val keywordsEnd = indexOfFirst { it !is Keyword }
    val declSpec = tokenContext(if (keywordsEnd == -1) tokStack.peek().size else keywordsEnd) {
      val storageSpecs = mutableListOf<Keyword>()
      val typeSpecs = mutableListOf<Keyword>()
      val typeQuals = mutableListOf<Keyword>()
      val funSpecs = mutableListOf<Keyword>()
      keywordsLoop@ for (tok in it) when ((tok as Keyword).value) {
        Keywords.TYPEDEF -> logger.throwICE("Typedef not implemented") { this }
        in storageClassSpecifier -> storageSpecs.add(tok)
        in typeSpecifier -> typeSpecs.add(tok)
        in typeQualifier -> typeQuals.add(tok)
        in funSpecifier -> funSpecs.add(tok)
        else -> break@keywordsLoop
      }
      DeclarationSpecifier(storageSpecs, typeSpecs, typeQuals, funSpecs)
    }
    eatList(declSpec.size)
    return declSpec
  }

  /**
   * An abstract version of [findParenMatch]. Even though that function calls through to this
   * one, the more general behaviour of this one is just the fact that it can accept both
   * [Punctuators] and [Keywords]. Otherwise, it works identically to [findParenMatch].
   * @see findParenMatch
   */
  private inline fun <reified T, E> findMatch(start: E, final: E, stopAtSemi: Boolean): Int
      where T : StaticToken, E : StaticTokenEnum {
    var hasParens = false
    var stack = 0
    val end = indexOfFirst {
      if (it::class != T::class) return@indexOfFirst false
      it as StaticToken
      when (it.enum) {
        start -> {
          hasParens = true
          stack++
          return@indexOfFirst false
        }
        final -> {
          // FIXME: error here if stack is 0
          stack--
          return@indexOfFirst stack == 0
        }
        Punctuators.SEMICOLON -> return@indexOfFirst stopAtSemi
        else -> return@indexOfFirst false
      }
    }
    if (end == -1 && !hasParens) {
      // This is the case where there aren't any lparens until the end
      return tokStack.peek().size
    }
    if (!hasParens) {
      // This is the case where there aren't any lparens until a semicolon
      return end
    }
    if (end == -1 || (tokStack.peek()[end] as StaticToken).enum != final) {
      parserDiagnostic {
        id = DiagnosticId.UNMATCHED_PAREN
        formatArgs(final.realName)
        if (end == -1) {
          column(colPastTheEnd(tokStack.peek().size))
        } else {
          errorOn(tokStack.peek()[end])
        }
      }
      parserDiagnostic {
        id = DiagnosticId.MATCH_PAREN_TARGET
        formatArgs(start.realName)
        errorOn(safeToken(0))
      }
      return -1
    }
    return end
  }

  /**
   * Find matching parenthesis in token list. Handles nested parens. Prints errors about unmatched
   * parens.
   * @param lparen the left paren: eg '(' or '[' or '{'
   * @param rparen the right paren: eg ')' or ']' or '}'
   * @param stopAtSemi whether or not to return -1 when hitting a semicolon
   * @return -1 if the parens are unbalanced or a [Punctuators.SEMICOLON] was found before they can
   * get balanced (and [stopAtSemi] is true), the size of the token stack if there were no parens,
   * or the (real) idx of the rightmost paren otherwise
   */
  private fun findParenMatch(lparen: Punctuators, rparen: Punctuators, stopAtSemi: Boolean = true) =
      findMatch<Punctuator, Punctuators>(lparen, rparen, stopAtSemi)

  /**
   * Parses the params in a function declaration.
   * Examples of what it parses:
   * void f(int a, int x);
   *        ^^^^^^^^^^^^
   * void g();
   *        (here this function gets nothing to parse, and returns an empty list)
   */
  private fun parseParameterList(endIdx: Int): List<ParameterDeclaration> = tokenContext(endIdx) {
    // No parameters; this is not an error case
    if (isEaten()) return@tokenContext emptyList()
    val params = mutableListOf<ParameterDeclaration>()
    while (!isEaten()) {
      val specs = parseDeclSpecifiers()
      if (specs.isEmpty()) {
        TODO("possible unimplemented grammar (old-style K&R functions?)")
      }
      // The parameter can have parens with commas in them
      // We're interested in the comma that comes after the parameter
      // So balance the parens, and look for the first comma after them
      // Also, we do not eat what we find; we're only searching for the end of the current param
      // Once found, parseDeclarator handles parsing the param and eating it
      val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      if (parenEndIdx == -1) {
        TODO("handle error case where there is an unmatched paren in the parameter list")
      }
      val commaIdx = indexOfFirst { c -> c == Punctuators.COMMA }
      val declarator = parseDeclarator(if (commaIdx == -1) it.size else commaIdx)
      params.add(ParameterDeclaration(specs, declarator))
      // Add param name to current scope (which can be either block scope or
      // function prototype scope)
      val declaratorName = declarator.name()
      if (declaratorName != null) newIdentifier(declaratorName)
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; found comma that separates params
        eat()
      }
    }
    return@tokenContext params
  }

  /**
   * This function parses a `declarator` nested in a `direct-declarator`.
   *
   * C standard: 6.7.6.1
   */
  private fun parseNestedDeclarator(): Declarator? {
    if (current().asPunct() != Punctuators.LPAREN) return null
    val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (end == -1) return ErrorDeclarator()
    // If the declarator slice will be empty, error out
    if (end - 1 == 0) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_DECL
        errorOn(safeToken(1))
      }
      eatToSemi()
      return ErrorDeclarator()
    }
    val declarator = parseDeclarator(end)
    if (declarator is ErrorNode) eatToSemi()
    return declarator
  }

  /**
   * This function parses the things that can come after a `direct-declarator`.
   * FIXME: parse in a loop, to catch things like `int v[12][23];`
   * C standard: 6.7.6.1
   */
  private fun parseDirectDeclaratorSuffixes(primary: Declarator): Declarator = when {
    isEaten() -> primary
    current().asPunct() == Punctuators.LPAREN -> {
      val rparenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      eat() // Get rid of "("
      // FIXME: we can return something better than an ErrorNode (have the ident)
      if (rparenIdx == -1) {
        // FIXME: maybe we should eat stuff here?
        ErrorDeclarator()
      } else scoped {
        val paramList = parseParameterList(rparenIdx)
        eat() // Get rid of ")"
        FunctionDeclarator(declarator = primary, params = paramList, scope = this, isVararg = false)
      }
    }
    current().asPunct() == Punctuators.LSQPAREN -> {
      val end = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
      if (end == -1) {
        ErrorDeclarator()
      } else {
        // FIXME: A.2.2/6.7.6 direct-declarator with square brackets
        logger.throwICE("Unimplemented grammar") { tokStack.peek() }
      }
    }
    else -> primary
  }

  /** C standard: 6.7.6.1 */
  private fun parseNameDeclarator(): NameDeclarator? {
    val id = current() as? Identifier ?: return null
    val name = NameDeclarator(IdentifierNode(id.name))
    eat() // The identifier token
    return name
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    if (it.isEmpty()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        column(colPastTheEnd(0))
      }
      return@tokenContext ErrorDeclarator()
    }
    val primaryDecl = parseNameDeclarator() ?: parseNestedDeclarator()
    if (primaryDecl == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        errorOn(safeToken(0))
      }
      return@tokenContext ErrorDeclarator()
    }
    return@tokenContext parseDirectDeclaratorSuffixes(primaryDecl)
  }

  /** C standard: 6.7.6.1 */
  private fun parseDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    // FIXME: missing pointer parsing
    val directDecl = parseDirectDeclarator(it.size)
    if (!isEaten()) {
      // FIXME: this should likely be an error
      logger.warn { "parseDirectDeclarator did not eat all of its tokens" }
    }
    return@tokenContext directDecl
  }

  // FIXME: return type will change with the initializer list
  private fun parseInitializer(): Expression {
    eat() // Get rid of "="
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      return ErrorExpression()
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      TODO("parse initializer-list (A.2.2/6.7.9)")
    }
    // Simple expression
    // parseExpr should print out the diagnostic in case there is no expr here
    return parseExpr(tokStack.peek().size) ?: ErrorExpression()
  }

  /**
   * Parses a declaration, including function declarations.
   * @return null if there is no declaration, or a [Declaration] otherwise
   */
  private fun parseDeclaration(): Declaration? {
    val declSpec = parseDeclSpecifiers()
    if (declSpec.isEmpty()) return null
    return RealDeclaration(declSpec, parseInitDeclaratorList())
  }

  /**
   * Parse a `init-declarator-list`, a part of a `declaration`.
   *
   * C standard: A.2.2
   * @param firstDecl if not null, this will be treated as the first declarator in the list that was
   * pre-parsed
   */
  private fun parseInitDeclaratorList(firstDecl: Declarator? = null): List<Declarator> {
    // FIXME typedef is to be handled specially, see 6.7.1 paragraph 5
    val declaratorList = mutableListOf<Declarator>()
    // If firstDecl is null, we act as if it was already processed
    var firstDeclUsed = firstDecl == null
    while (true) {
      val declarator = if (firstDeclUsed) {
        parseDeclarator(tokStack.peek().size)
      } else {
        firstDeclUsed = true
        firstDecl!!
      }
      if (declarator is ErrorDeclarator) {
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched paren in the initializer")
        }
        val stopIdx = indexOfFirst {
          it.asPunct() == Punctuators.COMMA || it.asPunct() == Punctuators.SEMICOLON
        }
        eatList(takeUntil(stopIdx).size)
      }
      if (isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        declaratorList.add(declarator)
        break
      }
      if (current().asPunct() == Punctuators.ASSIGN) {
        declaratorList.add(InitDeclarator(declarator, parseInitializer()))
      } else {
        declaratorList.add(declarator)
      }
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; there are chained `init-declarator`s
        eat()
        continue
      } else if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) {
        // Expected case; semi at the end of `declaration`
        eat()
        break
      } else {
        // Missing semicolon
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        break
      }
    }
    declaratorList.mapNotNull { it.name() }.forEach { newIdentifier(it) }
    if (declaratorList.isEmpty()) parserDiagnostic {
      id = DiagnosticId.MISSING_DECLARATIONS
      errorOn(safeToken(0))
    }
    return declaratorList
  }

  /**
   * C standard: A.2.3, 6.8.1
   * @return the [LabeledStatement] if it is there, or null if there is no such statement
   */
  private fun parseLabeledStatement(): Statement? {
    // FIXME: this only parses the first kind of labeled statement (6.8.1)
    if (current() !is Identifier || lookahead().asPunct() != Punctuators.COLON) return null
    val label = IdentifierNode((current() as Identifier).name)
    newIdentifier(label, isLabel = true)
    eatList(2) // Get rid of ident and COLON
    val labeled = parseStatement()
    if (labeled == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(tokStack.peek()[idxStack.peek() - 1])
      }
      return ErrorStatement()
    }
    return LabeledStatement(label, labeled)
  }

  /**
   * C standard: A.2.3, 6.8.4.1
   * @return the [IfStatement] if it is there, or null if it isn't
   */
  private fun parseIfStatement(): Statement? {
    if (current().asKeyword() != Keywords.IF) return null
    eat() // The 'if'
    val condParenEnd = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condParenEnd == -1) return ErrorStatement()
    eat() // The '(' from the if
    val condExpr = parseExpr(condParenEnd)
    val cond = if (condExpr == null) {
      // Eat everything between parens
      tokenContext(condParenEnd) {
        while (!isEaten()) eat()
      }
      ErrorExpression()
    } else {
      condExpr
    }
    eat() // The ')' from the if
    val statementSuccess = if (!isEaten() && current().asKeyword() == Keywords.ELSE) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      ErrorStatement()
    } else {
      val statement = parseStatement()
      if (statement == null) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_STATEMENT
          errorOn(safeToken(0))
        }
        // Attempt to eat the error
        while (!isEaten() &&
            current().asPunct() != Punctuators.SEMICOLON &&
            current().asKeyword() != Keywords.ELSE) eat()
        ErrorStatement()
      } else {
        statement
      }
    }
    if (!isEaten() && current().asKeyword() == Keywords.ELSE) {
      eat() // The 'else'
      val elseStatement = parseStatement()
      val statementFailure = if (elseStatement == null) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_STATEMENT
          errorOn(safeToken(0))
        }
        // Eat until the next thing
        eatToSemi()
        if (!isEaten()) eat()
        ErrorStatement()
      } else {
        elseStatement
      }
      return IfStatement(cond, statementSuccess, statementFailure)
    } else {
      return IfStatement(cond, statementSuccess, null)
    }
  }

  /** Wraps [parseExpr] with a check for [Punctuators.SEMICOLON] at the end. */
  private fun parseExpressionStatement(): Expression? {
    val expr = parseExpr(tokStack.peek().size) ?: return null
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("expression")
        column(colPastTheEnd(0))
      }
    } else {
      eat() // The semicolon
    }
    return expr
  }

  /**
   * C standard: A.2.3, 6.8.6.1
   *
   * @return null if there is no goto, or the [GotoStatement] otherwise
   */
  private fun parseGotoStatement(): Statement? {
    if (current().asKeyword() != Keywords.GOTO) return null
    eat()
    if (current() !is Identifier) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorStatement()
    } else {
      val ident = IdentifierNode((current() as Identifier).name)
      eat() // The ident
      if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("goto statement")
          column(colPastTheEnd(0))
        }
        eatToSemi()
        if (!isEaten()) eatToSemi()
      } else {
        eat() // The ';'
      }
      return GotoStatement(ident)
    }
  }

  /**
   * C standard: A.2.3, 6.8.6.2
   *
   * @return null if there is no continue, the [ContinueStatement] otherwise
   */
  private fun parseContinue(): ContinueStatement? {
    if (current().asKeyword() != Keywords.CONTINUE) return null
    eat()
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("continue statement")
        column(colPastTheEnd(0))
      }
    } else {
      eat() // The ';'
    }
    return ContinueStatement()
  }

  /** C standard: A.2.3, 6.8.6.3 */
  private fun parseBreak(): BreakStatement? {
    if (current().asKeyword() != Keywords.BREAK) return null
    eat()
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("break statement")
        column(colPastTheEnd(0))
      }
    } else {
      eat() // The ';'
    }
    return BreakStatement()
  }

  /** C standard: A.2.3, 6.8.6.3 */
  private fun parseReturn(): ReturnStatement? {
    if (current().asKeyword() != Keywords.RETURN) return null
    eat()
    val semiIdx = indexOfFirst { it.asPunct() == Punctuators.SEMICOLON }
    val finalIdx = if (semiIdx == -1) tokStack.peek().size else semiIdx
    val expr = parseExpr(finalIdx)
    if (semiIdx == -1 || (!isEaten() && current().asPunct() != Punctuators.SEMICOLON)) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("return statement")
        column(colPastTheEnd(0))
      }
      return ReturnStatement(expr)
    }
    eat() // The ';'
    return ReturnStatement(expr)
  }

  /**
   * C standard: 6.8.5
   *
   * @return null if there is no while, the [WhileStatement] otherwise
   */
  private fun parseWhile(): Statement? {
    if (current().asKeyword() != Keywords.WHILE) return null
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        errorOn(safeToken(0))
      }
      val end = indexOfFirst {
        it.asPunct() == Punctuators.LBRACKET || it.asPunct() == Punctuators.SEMICOLON
      }
      eatList(takeUntil(end).size)
      if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) eat()
      return ErrorStatement()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return ErrorStatement()
    val cond = parseExpr(rparen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatList(takeUntil(rparen).size)
      ErrorExpression()
    } else {
      cond
    }
    eat() // The ')'
    val statement = parseStatement()
    val loopable = if (statement == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (!isEaten()) eat()
      ErrorStatement()
    } else {
      statement
    }
    return WhileStatement(condition, loopable)
  }

  /**
   * C standard: 6.8.5
   *
   * @return null if there is no while, the [DoWhileStatement] otherwise
   */
  private fun parseDoWhile(): Statement? {
    if (current().asKeyword() != Keywords.DO) return null
    val theWhile = findMatch<Keyword, Keywords>(Keywords.DO, Keywords.WHILE, stopAtSemi = false)
    eat() // The DO
    if (theWhile == -1) return ErrorStatement()
    val statement = tokenContext(theWhile) { parseStatement() }
    val loopable = if (statement == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      val end = indexOfFirst {
        it.asPunct() == Punctuators.SEMICOLON || it.asKeyword() == Keywords.WHILE
      }
      if (end == -1) eatToSemi()
      eatList(takeUntil(end).size)
      ErrorStatement()
    } else {
      statement
    }
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return DoWhileStatement(ErrorExpression(), loopable)
    }
    val condRParen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condRParen == -1) return DoWhileStatement(ErrorExpression(), loopable)
    eat() // The '('
    val cond = parseExpr(condRParen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatList(takeUntil(condRParen).size)
      ErrorExpression()
    } else {
      cond
    }
    eat() // The ')'
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("do/while statement")
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()

    } else {
      eat() // The ';'
    }
    return DoWhileStatement(condition, loopable)
  }

  /**
   * C standard: 6.8.5, 6.8.5.3
   *
   * @return null if there is no while, the [ForStatement] otherwise
   */
  private fun parseFor(): Statement? {
    if (current().asKeyword() != Keywords.FOR) return null
    eat() // The FOR
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.FOR.keyword)
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorStatement()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return ErrorStatement()
    // The 3 components of a for loop
    val (clause1, expr2, expr3) = tokenContext(rparen) {
      val firstSemi = indexOfFirst { c -> c.asPunct() == Punctuators.SEMICOLON }
      if (firstSemi == -1) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_IN_FOR
          if (it.isNotEmpty()) errorOn(safeToken(it.size))
          else errorOn(tokStack[tokStack.size - 2][rparen])
        }
        return@tokenContext Triple(ErrorInitializer(), ErrorExpression(), ErrorExpression())
      }
      // Handle the case where we have an empty clause1
      val clause1 = if (firstSemi == 0) {
        EmptyInitializer()
      } else {
        // parseDeclaration wants to see the semicolon as well, so +1
        tokenContext(firstSemi + 1) {
          parseDeclaration()?.let { d -> DeclarationInitializer(d) }
        } ?: parseExpr(firstSemi)?.let { e -> ExpressionInitializer(e) } ?: ErrorInitializer()
      }
      // We only eat the first ';' if parseDeclaration didn't do that
      if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) eat()
      val secondSemi = indexOfFirst { c -> c.asPunct() == Punctuators.SEMICOLON }
      if (secondSemi == -1) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_IN_FOR
          errorOn(safeToken(it.size))
        }
        return@tokenContext Triple(clause1, ErrorExpression(), ErrorExpression())
      }
      // Handle the case where we have an empty expr2
      val expr2 = if (secondSemi == firstSemi + 1) null else parseExpr(secondSemi)
      eat() // The second ';'
      // Handle the case where we have an empty expr3
      val expr3 =
          if (secondSemi + 1 == tokStack.peek().size) null else parseExpr(tokStack.peek().size)
      if (!isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.UNEXPECTED_IN_FOR
          errorOn(safeToken(0))
        }
      }
      return@tokenContext Triple(clause1, expr2, expr3)
    }
    val remainders = takeUntil(rparen)
    if (remainders.isNotEmpty()) {
      // Eat everything inside the for's parens
      eatList(remainders.size)
    }
    eat() // The ')'
    val loopable = parseStatement()
    if (loopable == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (!isEaten()) eat()
      return ForStatement(clause1, expr2, expr3, ErrorExpression())
    }
    return ForStatement(clause1, expr2, expr3, loopable)
  }

  /**
   * C standard: A.2.3
   * @return null if no statement was found, or the [Statement] otherwise
   */
  private fun parseStatement(): Statement? {
    if (isEaten()) return null
    if (current().asPunct() == Punctuators.SEMICOLON) {
      eat()
      return Noop()
    }
    return parseLabeledStatement()
        ?: parseCompoundStatement()
        ?: parseIfStatement()
        ?: parseGotoStatement()
        ?: parseWhile()
        ?: parseDoWhile()
        ?: parseFor()
        ?: parseContinue()
        ?: parseBreak()
        ?: parseReturn()
        ?: parseExpressionStatement()
        ?: TODO("unimplemented grammar")
  }

  /**
   * Parses a compound-statement, including the { } brackets.
   *
   * C standard: A.2.3
   * @param functionScope the scope of the function for which this is a block, null otherwise
   * @return null if there is no compound statement, or the [CompoundStatement] otherwise
   */
  private fun parseCompoundStatement(functionScope: LexicalScope? = null): Statement? {
    if (current().asPunct() != Punctuators.LBRACKET) return null
    val rbracket = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET, false)
    eat() // Get rid of '{'
    if (rbracket == -1) {
      // Try to recover
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorStatement()
    }
    fun parseCompoundItems(scope: LexicalScope): CompoundStatement {
      val items = mutableListOf<BlockItem>()
      while (!isEaten()) {
        val item = parseDeclaration()?.let { d -> DeclarationItem(d) }
            ?: parseStatement()?.let { s -> StatementItem(s) }
            ?: continue
        items.add(item)
      }
      return CompoundStatement(items, scope)
    }
    // Parsing the items happens both inside a lexical scope and inside a token context
    val compound = (functionScope ?: LexicalScope()).withScope {
      tokenContext(rbracket) { parseCompoundItems(this) }
    }
    eat() // Get rid of '}'
    return compound
  }

  /**
   * Parses a function _definition_. That means everything after the declarator, so not the
   * `declaration-specifiers` or the [FunctionDeclarator], but the `compound-statement` and
   * optionally the `declaration-list` (for old-style functions). Function _declarations_ are
   * **not** parsed here (see [parseDeclaration]).
   *
   * C standard: A.2.4, A.2.2, 6.9.1
   * @see parseDeclSpecifiers
   * @see parseDeclarator
   * @param declSpec pre-parsed declaration specifier
   * @param funDecl pre-parsed function declarator
   * @return the [FunctionDefinition]
   */
  private fun parseFunctionDefinition(declSpec: DeclarationSpecifier,
                                      funDecl: FunctionDeclarator): FunctionDefinition {
    if (current().asPunct() != Punctuators.LBRACKET) {
      TODO("possible unimplemented grammar (old-style K&R functions?)")
    }
    val block = parseCompoundStatement(funDecl.scope) ?: ErrorStatement()
    return FunctionDefinition(declSpec, funDecl, block)
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    val declSpec = parseDeclSpecifiers()
    if (declSpec.isEmpty()) {
      // If we got here it means the current thing isn't a translation unit
      // So spit out an error and eat tokens
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return translationUnit()
    }
    val declarator = parseDeclarator(tokStack.peek().size)
    if (declarator is FunctionDeclarator && current().asPunct() != Punctuators.SEMICOLON) {
      declarator.name()?.let { name -> newIdentifier(name) }
      root.addExternalDeclaration(parseFunctionDefinition(declSpec, declarator))
    } else {
      val decl = RealDeclaration(declSpec, parseInitDeclaratorList(declarator))
      root.addExternalDeclaration(decl)
    }
    if (isEaten()) return
    else translationUnit()
  }
}
