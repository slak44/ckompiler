package slak.test

import slak.ckompiler.Diagnostic
import slak.ckompiler.DiagnosticId
import slak.ckompiler.SourceFileName
import slak.ckompiler.lexer.*
import slak.ckompiler.parser.*
import java.io.File
import kotlin.test.assertEquals

internal fun Lexer.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), diags)
internal fun Parser.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal val <T : Any> T.source get() = "<test/${javaClass.simpleName}>"
internal fun <T : Any> T.resource(s: String) = File(javaClass.classLoader.getResource(s).file)

internal fun prepareCode(s: String, source: SourceFileName): Parser {
  val lexer = Lexer(s, source)
  lexer.assertNoDiagnostics()
  return Parser(lexer.tokens, source, s)
}

internal val List<Diagnostic>.ids get() = map { it.id }

internal fun Parser.assertDiags(vararg ids: DiagnosticId) = assertEquals(ids.toList(), diags.ids)

internal fun int(i: Long): IntegerConstantNode = IntegerConstantNode(i, IntegralSuffix.NONE)

internal fun double(f: Double): FloatingConstantNode = FloatingConstantNode(f, FloatingSuffix.NONE)

internal val Keywords.kw get() = Keyword(this)

internal val int = DeclarationSpecifier(typeSpec = IntType(Keywords.INT.kw))
internal val double = DeclarationSpecifier(typeSpec = DoubleType(Keywords.DOUBLE.kw))
internal val longLong = DeclarationSpecifier(typeSpec = LongLong(Keywords.LONG.kw))
internal val uLongLong =
    DeclarationSpecifier(typeSpec = UnsignedLongLong(Keywords.UNSIGNED.kw))
internal val longDouble = DeclarationSpecifier(typeSpec = LongDouble(Keywords.LONG.kw))
internal val signedChar = DeclarationSpecifier(typeSpec = SignedChar(Keywords.SIGNED.kw))

internal infix fun ASTNode.assertEquals(rhs: ASTNode) = assertEquals(this, rhs)

internal fun name(s: String): IdentifierNode = IdentifierNode(s)
internal fun nameDecl(s: String) = NameDeclarator(name(s))

internal fun ptr(d: Declarator): Declarator {
  // Yes, the nested empty lists are correct
  d.setIndirection(listOf(listOf()))
  return d
}

internal fun ptr(s: String) = ptr(nameDecl(s))

internal fun Declarator.withPtrs(vararg q: TypeQualifierList): Declarator {
  setIndirection(q.asList())
  return this
}

internal fun String.withPtrs(vararg q: TypeQualifierList) = nameDecl(this).withPtrs(*q)

internal infix fun <T> String.assign(it: T) = InitDeclarator(nameDecl(this), parseDSLElement(it))

internal infix fun DeclarationSpecifier.declare(decl: Declarator) =
    Declaration(this, listOf(decl))

@JvmName("declareDeclarators")
internal infix fun DeclarationSpecifier.declare(list: List<Declarator>) =
    Declaration(this, list.map { it })

@JvmName("declareStrings")
internal infix fun DeclarationSpecifier.declare(list: List<String>) =
    Declaration(this, list.map { nameDecl(it) })

internal infix fun DeclarationSpecifier.func(decl: Declarator) =
    Declaration(this, listOf(decl))

internal infix fun DeclarationSpecifier.func(s: String) =
    Declaration(this, listOf(s withParams emptyList()))

internal infix fun DeclarationSpecifier.declare(s: String) =
    Declaration(this, listOf(nameDecl(s)))

internal infix fun DeclarationSpecifier.param(s: String) = ParameterDeclaration(this, nameDecl(s))

private fun String.withParams(params: List<ParameterDeclaration>,
                              variadic: Boolean): FunctionDeclarator {
  val scope = params.mapTo(mutableListOf()) { it.name()!! }.let {
    val s = LexicalScope()
    s.idents += it
    s
  }
  return FunctionDeclarator(nameDecl(this), params, variadic = variadic, scope = scope)
}

internal infix fun String.withParams(params: List<ParameterDeclaration>) = withParams(params, false)
internal infix fun String.withParamsV(params: List<ParameterDeclaration>) = withParams(params, true)

internal infix fun Declaration.body(s: Statement): FunctionDefinition {
  if (s !is CompoundStatement && s !is ErrorStatement) {
    throw IllegalArgumentException("Not compound or error")
  }
  if (declaratorList.size != 1) throw IllegalArgumentException("Not function")
  val d = declaratorList[0] as? FunctionDeclarator ?: throw IllegalArgumentException("Not function")
  val st = s as? CompoundStatement
  val scope = if (st == null || st.items.isEmpty()) d.scope else st.scope
  val fdecl = FunctionDeclarator(d.declarator, d.params, d.variadic, scope)
  val newCompound = if (st == null) s else CompoundStatement(st.items, scope)
  return FunctionDefinition(declSpecs, fdecl, newCompound)
}

internal infix fun Declaration.body(list: List<BlockItem>) = this body list.compound()

internal fun ifSt(e: Expression, success: () -> Statement) = IfStatement(e, success(), null)
internal fun ifSt(e: Expression, success: CompoundStatement) = IfStatement(e, success, null)

internal infix fun IfStatement.elseSt(failure: () -> Statement) =
    IfStatement(this.cond, this.success, failure())

internal infix fun IfStatement.elseSt(failure: CompoundStatement) =
    IfStatement(this.cond, this.success, failure)

internal fun returnSt(e: Expression) = ReturnStatement(e)

internal fun <T> compoundOf(vararg elements: T) = listOf(*elements).compound()

internal fun emptyCompound() = CompoundStatement(emptyList(), LexicalScope())

internal fun <T> List<T>.compound() = CompoundStatement(map {
  when (it) {
    is Statement -> StatementItem(it)
    is Declaration -> DeclarationItem(it)
    is TagSpecifier -> DeclarationItem(Declaration(it.toSpec(), emptyList()))
    else -> throw IllegalArgumentException("Bad type")
  }
}, with(LexicalScope()) {
  forEach {
    when (it) {
      is Declaration -> idents += it.identifiers()
      is ForStatement -> {
        val names = (it.init as? DeclarationInitializer)?.value?.identifiers()
        if (names != null) idents += names
      }
      is LabeledStatement -> labels += it.label
      is Statement -> { /* Do nothing intentionally */ }
      is TagSpecifier -> tagNames += it
      else -> throw IllegalArgumentException("Bad type")
    }
  }
  this
})

internal fun whileSt(e: Expression, loopable: Statement) = WhileStatement(e, loopable)
internal fun whileSt(e: Expression, loopable: () -> Statement) = whileSt(e, loopable())
internal infix fun Statement.asDoWhile(cond: Expression) = DoWhileStatement(cond, this)

internal fun forSt(e: ForInitializer,
                   cond: Expression?,
                   cont: Expression?,
                   loopable: Statement): ForStatement {
  return ForStatement(e, cond, cont, loopable)
}

internal fun forSt(e: Expression,
                   cond: Expression?,
                   cont: Expression?,
                   loopable: Statement): ForStatement {
  return ForStatement(ExpressionInitializer(e), cond, cont, loopable)
}

internal fun forSt(e: Declaration,
                   cond: Expression?,
                   cont: Expression?,
                   loopable: Statement): ForStatement {
  return ForStatement(DeclarationInitializer(e), cond, cont, loopable)
}

internal infix fun String.labeled(s: Statement) = LabeledStatement(IdentifierNode(this), s)

internal fun goto(s: String) = GotoStatement(IdentifierNode(s))

internal infix fun String.call(l: List<Expression>) = FunctionCall(name(this), l.map { it })

internal fun struct(name: String?, decls: List<Declaration>): StructDefinition {
  val d = decls.map { (declSpecs, declaratorList) ->
    Declaration(declSpecs, declaratorList.map {
      if (it is StructDeclarator) it
      else StructDeclarator(it, null)
    })
  }
  return StructDefinition(
      name = name?.let { name(it) }, decls = d, tagKindKeyword = Keywords.STRUCT.kw)
}

internal fun TagSpecifier.toSpec() = DeclarationSpecifier(typeSpec = this)

internal infix fun String.bitSize(expr: Expression) = StructDeclarator(nameDecl(this), expr)
internal infix fun String.bitSize(it: Long) = this bitSize int(it)

internal fun String.typedefBy(ds: DeclarationSpecifier): DeclarationSpecifier {
  if (!ds.isTypedef()) throw IllegalArgumentException("Not typedef")
  val td = TypedefName(ds, emptyList(), name(this))
  return DeclarationSpecifier(typeSpec = TypedefNameSpecifier(name(this), td))
}

internal infix fun <LHS, RHS> LHS.add(that: RHS) = this to that with Operators.ADD
internal infix fun <LHS, RHS> LHS.sub(that: RHS) = this to that with Operators.SUB
internal infix fun <LHS, RHS> LHS.mul(that: RHS) = this to that with Operators.MUL
internal infix fun <LHS, RHS> LHS.div(that: RHS) = this to that with Operators.DIV

private fun <T> parseDSLElement(it: T): Expression {
  return when (it) {
    is Expression -> it
    is Int -> int(it.toLong())
    is Double -> double(it.toDouble())
    is String -> name(it)
    else -> throw IllegalArgumentException("Bad types")
  }
}

internal infix fun <LHS, RHS> Pair<LHS, RHS>.with(op: Operators): BinaryExpression {
  val lhs = parseDSLElement(first)
  val rhs = parseDSLElement(second)
  return BinaryExpression(op, lhs, rhs)
}

internal infix fun <T> Operators.apply(it: T) = UnaryExpression(this, parseDSLElement(it))
