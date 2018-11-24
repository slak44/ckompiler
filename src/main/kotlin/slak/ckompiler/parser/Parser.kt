package slak.ckompiler.parser

import mu.KotlinLogging
import slak.ckompiler.*
import java.util.*
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * Parses a translation unit.
 *
 * C standard: A.2.4, 6.9
 *
 * @param tokens list of tokens to parse
 * @param srcFileName the name of the file in which the tokens were extracted from
 * @param tokStartIdxes a list of indices in the original source string, for the start of each token
 */
class Parser(tokens: List<Token>,
             private val srcFileName: SourceFileName,
             private val srcText: String,
             private val tokStartIdxes: List<Int>) {
  private val tokStack = Stack<List<Token>>()
  private val idxStack = Stack<Int>()
  val diags = mutableListOf<Diagnostic>()
  val root = RootNode()

  init {
    tokStack.push(tokens)
    idxStack.push(0)
    translationUnit()
    diags.forEach { it.print() }
  }

  companion object {
    private val logger = KotlinLogging.logger("Parser")
    private val storageClassSpecifier =
        listOf(Keywords.EXTERN, Keywords.STATIC, Keywords.AUTO, Keywords.REGISTER)
    private val typeSpecifier = listOf(Keywords.VOID, Keywords.CHAR, Keywords.SHORT, Keywords.INT,
        Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE, Keywords.SIGNED, Keywords.UNSIGNED,
        Keywords.BOOL, Keywords.COMPLEX)
  }

  /**
   * Get the [IntRange] spanned by the [Token] in the original code string.
   * FIXME: handle the many issues with offsets
   * FIXME: write tests for the ranges, and make sure the offsets in the diags are correct
   * @param offset the offset in the topmost token list, eg -1 to take the token before the one
   * given by [current], or 1 for the one in [lookahead].
   */
  private fun range(offset: Int): IntRange {
    val startIdx = tokStartIdxes[idxStack.peek() + offset]
    val endTokenIdx = min(idxStack.peek() + offset, tokStack.peek().size - 1)
    return startIdx until startIdx + tokStack.peek()[endTokenIdx].consumedChars
  }

  /**
   * When all the tokens have been eaten, get the column in the original code string, plus one.
   */
  private fun colPastTheEnd(): Int {
    return tokStartIdxes[idxStack.peek() - 1] + 1
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
  private fun parseExpr(endIdx: Int): EitherNode<Expression>? = tokenContext(endIdx) {
    val primary = parsePrimaryExpr().ifNull {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_PRIMARY
        columns(range(1))
      }
      return@tokenContext null
    }
    return@tokenContext parseExprImpl(primary, 0)
  }

  private fun parseExprImpl(lhsInit: EitherNode<Expression>,
                            minPrecedence: Int): EitherNode<Expression> {
    var lhs = lhsInit
    while (true) {
      if (isEaten()) break
      val op = current().asBinaryOperator() ?: break
      if (op.precedence < minPrecedence) break
      eat()
      var rhs = parsePrimaryExpr().ifNull {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_PRIMARY
          columns(range(0))
        }
        return ErrorNode()
      }
      while (true) {
        if (isEaten()) break
        val innerOp = current().asBinaryOperator() ?: break
        if (innerOp.precedence <= op.precedence &&
            !(innerOp.assoc == Associativity.RIGHT_TO_LEFT && innerOp.precedence == op.precedence)) {
          break
        }
        rhs = parseExprImpl(rhs, innerOp.precedence)
      }
      lhs = BinaryExpression(op, lhs, rhs).wrap()
    }
    return lhs
  }

  private fun parseBaseExpr(): EitherNode<Expression>? = when {
    // FIXME: implement generic-selection (A.2.1/6.5.1.1)
    current().asPunct() == Punctuators.RPAREN -> {
      // This usually happens when there are unmatched parens
      eat()
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        columns(range(0))
      }
      ErrorNode()
    }
    current().asPunct() == Punctuators.LPAREN -> {
      if (lookahead().asPunct() == Punctuators.RPAREN) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_EXPR
          columns(range(1))
        }
        ErrorNode()
      } else {
        val endParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (endParenIdx == -1) {
          eatToSemi()
          ErrorNode()
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
    }?.wrap()
  }

  private fun parseArgumentExprList(): List<EitherNode<Expression>> {
    val funcArgs = mutableListOf<EitherNode<Expression>>()
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

  private fun parsePostfixExpression(): EitherNode<Expression>? {
    // FIXME: implement initializer-lists (6.5.2)
    val expr = parseBaseExpr()
    return when {
      isEaten() || expr == null -> return expr
      current().asPunct() == Punctuators.LSQPAREN -> {
        TODO("implement subscript operator")
      }
      current().asPunct() == Punctuators.LPAREN -> {
        return FunctionCall(expr, parseArgumentExprList()).wrap()
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
        if (c == Punctuators.INC) PostfixIncrement(expr).wrap()
        else PostfixDecrement(expr).wrap()
      }
      else -> return expr
    }
  }

  private fun parseUnaryExpression(): EitherNode<Expression>? = when {
    current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
      val c = current().asPunct()
      eat() // The prefix op
      val expr = parseUnaryExpression() ?: ErrorNode()
      if (c == Punctuators.INC) PrefixIncrement(expr).wrap()
      else PrefixDecrement(expr).wrap()
    }
    current().asUnaryOperator() != null -> {
      val c = current().asUnaryOperator()!!
      eat() // The unary op
      val expr = parsePrimaryExpr() ?: ErrorNode()
      UnaryExpression(c, expr).wrap()
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
        isEaten() -> ErrorNode()
        current().asPunct() == Punctuators.LPAREN -> {
          TODO("implement `sizeof ( type-name )` expressions")
        }
        else -> SizeofExpression(parseUnaryExpression()
            ?: ErrorNode()).wrap()
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
   * @return null if no primary was found, or the [Expression] otherwise (this doesn't return a
   * [PrimaryExpression] because `( expression )` is a primary expression in itself)
   */
  private fun parsePrimaryExpr(): EitherNode<Expression>? {
    if (isEaten()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        // FIXME: find correct column
      }
      return ErrorNode()
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
  private fun parseTerminal(): Terminal? {
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
            columns(range(0))
          }
          0
        }
        return CharacterConstantNode(char, tok.encoding)
      }
      is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding)
      else -> return null
    }
  }

  private fun diagDuplicate(k: Keywords) = parserDiagnostic {
    id = DiagnosticId.DUPLICATE_DECL_SPEC
    formatArgs(k.keyword)
    columns(range(0))
  }

  private fun diagIncompat(k: Keywords) = parserDiagnostic {
    id = DiagnosticId.INCOMPATIBLE_DECL_SPEC
    formatArgs(k.keyword)
    columns(range(0))
  }

  private fun diagNotSigned(k: Keywords) = parserDiagnostic {
    id = DiagnosticId.TYPE_NOT_SIGNED
    formatArgs(k)
    columns(range(0))
  }

  /**
   * FIXME missing type specifiers (A.2.2/6.7.2):
   * 1. atomic-type-specifier (6.7.2.4)
   * 2. struct-or-union-specifier (6.7.2.1)
   * 3. enum-specifier (6.7.2.2)
   * 4. typedef-name (6.7.8)
   */
  private fun parseTypeSpecifier(typeSpec: List<Keywords>): TypeSpecifier? {
    if (typeSpec.isEmpty()) {
      parserDiagnostic {
        id = DiagnosticId.MISSING_TYPE_SPEC
        columns(range(0))
      }
      return null
    }

    // FIXME we are now going to pretend this implementation is finished, correct, complete,
    // standards-compliant, and reports sensible errors (lmao)

    val isSigned = typeSpec.contains(Keywords.SIGNED)
    val isUnsigned = typeSpec.contains(Keywords.UNSIGNED)
    if (isSigned && isUnsigned) {
      diagIncompat(Keywords.SIGNED)
      return null
    }
    if (typeSpec.contains(Keywords.VOID)) return TypeSpecifier.VOID
    if (typeSpec.contains(Keywords.FLOAT)) return TypeSpecifier.FLOAT
    if (typeSpec.contains(Keywords.LONG) && typeSpec.contains(Keywords.DOUBLE))
      return TypeSpecifier.LONG_DOUBLE
    if (typeSpec.contains(Keywords.DOUBLE)) return TypeSpecifier.DOUBLE

    if (typeSpec.contains(Keywords.CHAR)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_CHAR
      else TypeSpecifier.SIGNED_CHAR
    }
    if (typeSpec.contains(Keywords.SHORT)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_SHORT
      else TypeSpecifier.SIGNED_SHORT
    }
    // RIP long long
    if (typeSpec.contains(Keywords.LONG)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_LONG
      else TypeSpecifier.SIGNED_LONG
    }
    if (typeSpec.contains(Keywords.INT)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_INT
      else TypeSpecifier.SIGNED_INT
    }
    return null // Sure why not
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDeclSpecifiers(): DeclarationSpecifier {
    val typeSpec = mutableListOf<Keywords>()
    var storageSpecifier: Keywords? = null
    var hasThreadLocal = false
    var hasConst = false
    var hasRestrict = false
    var hasVolatile = false
    var hasAtomic = false
    var hasInline = false
    var hasNoReturn = false

    var hitError = false
    var declSpecTokenCount = 0
    while (current() is Keyword) {
      val k = (current() as Keyword).value
      when (k) {
        Keywords.COMPLEX -> {
          parserDiagnostic {
            id = DiagnosticId.UNSUPPORTED_COMPLEX
            columns(range(0))
          }
          hitError = true
        }
        in typeSpecifier -> typeSpec.add(k)
        Keywords.THREAD_LOCAL -> {
          if (hasThreadLocal) diagDuplicate(k)
          else if (storageSpecifier != null && storageSpecifier != Keywords.EXTERN &&
              storageSpecifier != Keywords.STATIC) {
            diagIncompat(storageSpecifier)
            hitError = true
          }
          hasThreadLocal = true
        }
        in storageClassSpecifier -> {
          if (k == storageSpecifier) diagDuplicate(k)
          else if (storageSpecifier != null) {
            diagIncompat(storageSpecifier)
            hitError = true
          } else if (hasThreadLocal &&
              (k != Keywords.EXTERN && k != Keywords.STATIC)) {
            diagIncompat(Keywords.THREAD_LOCAL)
            hitError = true
          }
          storageSpecifier = k
        }
        Keywords.CONST -> {
          if (hasConst) diagDuplicate(k)
          hasConst = true
        }
        Keywords.RESTRICT -> {
          if (hasRestrict) diagDuplicate(k)
          hasRestrict = true
        }
        Keywords.VOLATILE -> {
          if (hasVolatile) diagDuplicate(k)
          hasVolatile = true
        }
        Keywords.ATOMIC -> {
          if (hasAtomic) diagDuplicate(k)
          hasAtomic = true
        }
        Keywords.INLINE -> {
          if (hasInline) diagDuplicate(k)
          hasInline = true
        }
        Keywords.NORETURN -> {
          if (hasNoReturn) diagDuplicate(k)
          hasNoReturn = true
        }
        Keywords.TYPEDEF -> logger.throwICE("Typedef not implemented") { this }
        else -> null
      } ?: break
      eat()
      declSpecTokenCount++
    }

    if (declSpecTokenCount == 0) return MissingDeclarationSpecifier
    if (hitError) return ErrorDeclarationSpecifier

    val ts = parseTypeSpecifier(typeSpec) ?: return ErrorDeclarationSpecifier

    return RealDeclarationSpecifier(storageSpecifier, ts, hasThreadLocal,
        hasConst, hasRestrict, hasVolatile, hasAtomic, hasInline, hasNoReturn)
  }

  /**
   * An abstract version of [findParenMatch]. Even though that function calls through to this
   * one, the more general behaviour of this one is just the fact that it can accept both
   * [Punctuators] and [Keywords]. Otherwise, it works identically to [findParenMatch].
   * @see findParenMatch
   */
  private fun <T : StaticToken, E : StaticTokenEnum> findMatch(tokenClass: KClass<T>,
                                                                                             start: E,
                                                                                             final: E,
                                                                                             stopAtSemi: Boolean): Int {
    var hasParens = false
    var stack = 0
    val end = indexOfFirst {
      if (it::class != tokenClass) return@indexOfFirst false
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
          column(colPastTheEnd())
        } else {
          columns(range(end - idxStack.peek()))
        }
      }
      parserDiagnostic {
        id = DiagnosticId.MATCH_PAREN_TARGET
        formatArgs(start.realName)
        columns(range(0))
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
      findMatch(Punctuator::class, lparen, rparen, stopAtSemi)

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
      // We don't precisely care if we have an error in the DeclarationSpecifier
      val specs = parseDeclSpecifiers()
      if (specs is MissingDeclarationSpecifier) {
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
          ?: TODO("handle error case with a null (error'd) declarator")
      params.add(ParameterDeclaration(specs, declarator))
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; found comma that separates params
        eat()
      }
    }
    return@tokenContext params
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): EitherNode<Declarator>? = tokenContext(endIdx) {
    if (it.isEmpty()) return@tokenContext null
    when {
      current().asPunct() == Punctuators.LPAREN -> {
        val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (end == -1) return@tokenContext ErrorNode()
        // If the declarator slice will be empty, error out
        if (end - 1 == 0) {
          parserDiagnostic {
            id = DiagnosticId.EXPECTED_DECL
            columns(range(1))
          }
          eatToSemi()
          return@tokenContext ErrorNode()
        }
        val declarator = parseDeclarator(end)
        if (declarator is ErrorNode) eatToSemi()
        // FIXME: handle case where there is more shit (eg LPAREN/LSQPAREN cases) after end
        return@tokenContext declarator
      }
      current() !is Identifier -> {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
          columns(range(0))
        }
        return@tokenContext ErrorNode()
      }
      current() is Identifier -> {
        val name = IdentifierNode((current() as Identifier).name)
        eat()
        when {
          isEaten() -> return@tokenContext name.wrap()
          current().asPunct() == Punctuators.LPAREN -> {
            val rparenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
            eat() // Get rid of "("
            // FIXME: we can return something better than an ErrorNode (have the ident)
            if (rparenIdx == -1) return@tokenContext ErrorNode()
            val paramList = parseParameterList(rparenIdx)
            eat() // Get rid of ")"
            return@tokenContext FunctionDeclarator(name.wrap(), paramList).wrap()
          }
          current().asPunct() == Punctuators.LSQPAREN -> {
            val end = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
            if (end == -1) return@tokenContext ErrorNode()
            // FIXME parse "1 until end" slice (A.2.2/6.7.6 direct-declarator)
            logger.throwICE("Unimplemented grammar") { tokStack.peek() }
          }
          else -> return@tokenContext name.wrap()
        }
      }
      // FIXME: Can't happen? current() either is or isn't an identifier
      else -> return@tokenContext null
    }
  }

  private fun parseDeclarator(endIdx: Int): EitherNode<Declarator>? {
    // FIXME missing pointer parsing
    return parseDirectDeclarator(endIdx)
  }

  // FIXME: return type will change with the initializer list
  private fun parseInitializer(): EitherNode<Expression>? {
    eat() // Get rid of "="
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        columns(range(0))
      }
      return ErrorNode()
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      TODO("parse initializer-list (A.2.2/6.7.9)")
    }
    // Simple expression
    return parseExpr(tokStack.peek().size)
  }

  /**
   * Parses a declaration, including function declarations.
   * @return null if there is no declaration, or a [Declaration] otherwise
   */
  private fun parseDeclaration(): EitherNode<Declaration>? {
    // FIXME typedef is to be handled specially, see 6.7.1 paragraph 5
    val declSpec = parseDeclSpecifiers()
    if (declSpec is MissingDeclarationSpecifier) return null
    // FIXME validate declSpecs according to standard 6.7.{1-6}
    val declaratorList = mutableListOf<InitDeclarator>()
    while (true) {
      val initDeclarator = parseDeclarator(tokStack.peek().size).ifNull {
        // This means that there were decl specs, but no declarator, which is a problem
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_DECL
          columns(range(0))
        }
        return@parseDeclaration ErrorNode()
      }
      if (initDeclarator is ErrorNode) {
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
          column(colPastTheEnd())
        }
        declaratorList.add(InitDeclarator(initDeclarator, null))
        break
      }
      val initializer = if (current().asPunct() == Punctuators.ASSIGN) parseInitializer() else null
      declaratorList.add(InitDeclarator(initDeclarator, initializer))
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; there are chained init-declarators
        eat()
        continue
      } else if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) {
        // Expected case; semi at the end of declaration
        eat()
        break
      } else {
        // Missing semicolon
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd())
        }
        break
      }
    }
    return Declaration(declSpec, declaratorList).wrap()
  }

  /**
   * C standard: A.2.3, 6.8.1
   * @return the [LabeledStatement] if it is there, or null if there is no such statement
   */
  private fun parseLabeledStatement(): EitherNode<LabeledStatement>? {
    // FIXME: this only parser the first kind of labeled statement (6.8.1)
    if (current() !is Identifier || lookahead().asPunct() != Punctuators.COLON) return null
    val label = IdentifierNode((current() as Identifier).name)
    eatList(2) // Get rid of ident and COLON
    val labeled = parseStatement()
    if (labeled == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        columns(range(-1))
      }
      return ErrorNode()
    }
    return LabeledStatement(label, labeled).wrap()
  }

  /**
   * C standard: A.2.3, 6.8.4.1
   * @return the [IfStatement] if it is there, or null if it isn't
   */
  private fun parseIfStatement(): EitherNode<IfStatement>? {
    if (current().asKeyword() != Keywords.IF) return null
    eat() // The 'if'
    val condParenEnd = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condParenEnd == -1) return ErrorNode()
    eat() // The '(' from the if
    val condExpr = parseExpr(condParenEnd)
    val cond = if (condExpr == null) {
      // Eat everything between parens
      tokenContext(condParenEnd) {
        while (!isEaten()) eat()
      }
      ErrorNode()
    } else {
      condExpr
    }
    eat() // The ')' from the if
    val statementSuccess = if (!isEaten() && current().asKeyword() == Keywords.ELSE) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        columns(range(0))
      }
      ErrorNode()
    } else {
      val statement = parseStatement()
      if (statement == null) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_STATEMENT
          columns(range(0))
        }
        // Attempt to eat the error
        while (!isEaten() &&
            current().asPunct() != Punctuators.SEMICOLON &&
            current().asKeyword() != Keywords.ELSE) eat()
        ErrorNode()
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
          columns(range(0))
        }
        // Eat until the next thing
        eatToSemi()
        if (!isEaten()) eat()
        ErrorNode()
      } else {
        elseStatement
      }
      return IfStatement(cond, statementSuccess, statementFailure).wrap()
    } else {
      return IfStatement(cond, statementSuccess, null).wrap()
    }
  }

  /** Wraps [parseExpr] with a check for [Punctuators.SEMICOLON] at the end. */
  private fun parseExpressionStatement(): EitherNode<Expression>? {
    val expr = parseExpr(tokStack.peek().size) ?: return null
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("expression")
        column(colPastTheEnd())
      }
    } else {
      eat() // The semicolon
    }
    return expr
  }

  /** C standard: A.2.3, 6.8.6.1 */
  private fun parseGotoStatement(): EitherNode<GotoStatement>? {
    if (current().asKeyword() != Keywords.GOTO) return null
    eat()
    if (current() !is Identifier) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT
        columns(range(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorNode()
    } else {
      val ident = IdentifierNode((current() as Identifier).name)
      eat() // The ident
      if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("goto statement")
          column(colPastTheEnd())
        }
        eatToSemi()
        if (!isEaten()) eatToSemi()
      } else {
        eat() // The ';'
      }
      return GotoStatement(ident).wrap()
    }
  }

  /** C standard: A.2.3, 6.8.6.2 */
  private fun parseContinue(): ContinueStatement? {
    if (current().asKeyword() != Keywords.CONTINUE) return null
    eat()
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("continue statement")
        column(colPastTheEnd())
      }
    } else {
      eat() // The ';'
    }
    return ContinueStatement
  }

  /** C standard: A.2.3, 6.8.6.3 */
  private fun parseBreak(): BreakStatement? {
    if (current().asKeyword() != Keywords.BREAK) return null
    eat()
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("break statement")
        column(colPastTheEnd())
      }
    } else {
      eat() // The ';'
    }
    return BreakStatement
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
        column(colPastTheEnd())
      }
      return ReturnStatement(expr)
    }
    eat() // The ';'
    return ReturnStatement(expr)
  }

  /** C standard: 6.8.5 */
  private fun parseWhile(): EitherNode<WhileStatement>? {
    if (current().asKeyword() != Keywords.WHILE) return null
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        columns(range(0))
      }
      val end = indexOfFirst {
        it.asPunct() == Punctuators.LBRACKET || it.asPunct() == Punctuators.SEMICOLON
      }
      eatList(takeUntil(end).size)
      if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) eat()
      return ErrorNode()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return ErrorNode()
    val cond = parseExpr(rparen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatList(takeUntil(rparen).size)
      ErrorNode()
    } else {
      cond
    }
    eat() // The ')'
    val statement = parseStatement()
    val loopable = if (statement == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        columns(range(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (!isEaten()) eat()
      ErrorNode()
    } else {
      statement
    }
    return WhileStatement(condition, loopable).wrap()
  }

  /** C standard: 6.8.5 */
  private fun parseDoWhile(): EitherNode<DoWhileStatement>? {
    if (current().asKeyword() != Keywords.DO) return null
    val theWhile = findMatch(Keyword::class, Keywords.DO, Keywords.WHILE, stopAtSemi = false)
    eat() // The DO
    if (theWhile == -1) return ErrorNode()
    val statement = tokenContext(theWhile) { parseStatement() }
    val loopable = if (statement == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        columns(range(0))
      }
      // Attempt to eat the error
      val end = indexOfFirst {
        it.asPunct() == Punctuators.SEMICOLON || it.asKeyword() == Keywords.WHILE
      }
      if (end == -1) eatToSemi()
      eatList(takeUntil(end).size)
      ErrorNode()
    } else {
      statement
    }
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        columns(range(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return DoWhileStatement(ErrorNode(), loopable).wrap()
    }
    val condRParen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condRParen == -1) return DoWhileStatement(ErrorNode(), loopable).wrap()
    eat() // The '('
    val cond = parseExpr(condRParen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatList(takeUntil(condRParen).size)
      ErrorNode()
    } else {
      cond
    }
    eat() // The ')'
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("do/while statement")
        columns(range(0))
      }
      eatToSemi()
      if (!isEaten()) eat()

    } else {
      eat() // The ';'
    }
    return DoWhileStatement(condition, loopable).wrap()
  }

  /** C standard: 6.8.5, 6.8.5.3 */
  private fun parseFor(): EitherNode<ForStatement>? {
    if (current().asKeyword() != Keywords.FOR) return null
    eat() // The FOR
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.FOR.keyword)
        columns(range(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorNode()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return ErrorNode()
    val (clause1, expr2, expr3) = tokenContext(rparen) {
      val firstSemi = indexOfFirst { c -> c.asPunct() == Punctuators.SEMICOLON }
      if (firstSemi == -1) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_IN_FOR
          if (it.isNotEmpty()) columns(range(0))
        }
        return@tokenContext Triple(ErrorNode(), ErrorNode(), ErrorNode())
      }
      // Handle the case where we have an empty clause1
      val clause1: EitherNode<ForInitializer>? = if (firstSemi == 0) {
        null
      } else {
        // parseDeclaration wants to see the semicolon as well
        tokenContext(firstSemi + 1) { parseDeclaration() } ?: parseExpr(firstSemi)
      }
      // We only eat the first ';' if parseDeclaration didn't do that
      if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) eat()
      val secondSemi = indexOfFirst { c -> c.asPunct() == Punctuators.SEMICOLON }
      if (secondSemi == -1) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_IN_FOR
          columns(range(0))
        }
        return@tokenContext Triple(clause1, ErrorNode(), ErrorNode())
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
          columns(range(0))
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
        columns(range(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (!isEaten()) eat()
      return ForStatement(clause1, expr2, expr3, ErrorNode()).wrap()
    }
    return ForStatement(clause1, expr2, expr3, loopable).wrap()
  }

  /**
   * C standard: A.2.3
   * @return null if no statement was found, or the [Statement] otherwise
   */
  private fun parseStatement(): EitherNode<Statement>? {
    if (isEaten()) return null
    if (current().asPunct() == Punctuators.SEMICOLON) {
      eat()
      return Noop.wrap()
    }
    return parseLabeledStatement()
        ?: parseCompoundStatement()
        ?: parseIfStatement()
        ?: parseGotoStatement()
        ?: parseWhile()
        ?: parseDoWhile()
        ?: parseFor()
        ?: parseContinue()?.wrap()
        ?: parseBreak()?.wrap()
        ?: parseReturn()?.wrap()
        ?: parseExpressionStatement()
        ?: TODO("unimplemented grammar")
  }

  /**
   * Parses a compound-statement, including the { } brackets.
   * C standard: A.2.3
   * @return null if there is no compound statement, or the [CompoundStatement] otherwise
   */
  private fun parseCompoundStatement(): EitherNode<CompoundStatement>? {
    if (current().asPunct() != Punctuators.LBRACKET) return null
    val rbracket = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET, false)
    eat() // Get rid of '{'
    if (rbracket == -1) {
      // Try to recover
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorNode()
    }
    val compound = tokenContext(rbracket) {
      val items = mutableListOf<EitherNode<BlockItem>>()
      while (!isEaten()) items.add(parseDeclaration() ?: parseStatement() ?: continue)
      CompoundStatement(items)
    }
    eat() // Get rid of '}'
    return compound.wrap()
  }

  /**
   * Parses a function _definition_. That includes the compound-statement. Function _declarations_
   * are not parsed here (see [parseDeclaration]).
   * C standard: A.2.4, A.2.2, 6.9.1
   * @return null if this is not a function definition, or a [FunctionDefinition] otherwise
   */
  private fun parseFunctionDefinition(): EitherNode<FunctionDefinition>? {
    val firstBracket = indexOfFirst { it.asPunct() == Punctuators.LBRACKET }
    // If no bracket is found, it isn't a function, it might be a declaration
    if (firstBracket == -1) return null
    val declSpec = parseDeclSpecifiers()
    if (declSpec is MissingDeclarationSpecifier) return null
    // FIXME finish validation of declSpec
    if (declSpec is RealDeclarationSpecifier &&
        declSpec.storageSpecifier != null &&
        declSpec.storageSpecifier != Keywords.STATIC &&
        declSpec.storageSpecifier != Keywords.EXTERN) {
      parserDiagnostic {
        id = DiagnosticId.ILLEGAL_STORAGE_CLASS_FUNC
        // FIXME debug data in declSpec
      }
    }
    val declarator = parseDeclarator(firstBracket)?.let {
      if (it is EitherNode.Value && it.value is FunctionDeclarator) {
        // FIXME: what diag to print here?
        return@let it.value.wrap()
      }
      return@let ErrorNode()
    } ?: ErrorNode()
    if (current().asPunct() != Punctuators.LBRACKET) {
      TODO("possible unimplemented grammar (old-style K&R functions?)")
    }
    val block = parseCompoundStatement()
        ?: return FunctionDefinition(declSpec, declarator, ErrorNode()).wrap()
    return FunctionDefinition(declSpec, declarator, block).wrap()
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    val res = parseFunctionDefinition()?.let {
      root.addExternalDeclaration(it)
    } ?: parseDeclaration()?.let {
      root.addExternalDeclaration(it)
    }
    if (res == null) {
      // If we got here it means the current thing isn't a translation unit
      // So spit out an error and eat tokens
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
        columns(range(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
    }
    if (isEaten()) return
    else translationUnit()
  }
}
