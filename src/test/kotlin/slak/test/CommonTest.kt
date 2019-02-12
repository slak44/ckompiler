package slak.test

import slak.ckompiler.Diagnostic
import slak.ckompiler.DiagnosticId
import slak.ckompiler.Preprocessor
import slak.ckompiler.SourceFileName
import slak.ckompiler.lexer.*
import slak.ckompiler.parser.*
import java.io.File
import kotlin.test.assertEquals

internal fun Preprocessor.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), diags)
internal fun Lexer.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal fun Parser.assertNoDiagnostics() = assertEquals(emptyList(), diags)
internal val <T : Any> T.source get() = "<test/${javaClass.simpleName}>"
internal fun <T : Any> T.resource(s: String) = File(javaClass.classLoader.getResource(s).file)

internal fun prepareCode(s: String, source: SourceFileName): Parser {
  val pp = Preprocessor(s, source)
  pp.assertNoDiagnostics()
  val lexer = Lexer(pp.alteredSourceText, source)
  lexer.assertNoDiagnostics()
  return Parser(lexer.tokens, source, s)
}

internal val List<Diagnostic>.ids get() = map { it.id }

internal fun Parser.assertDiags(vararg ids: DiagnosticId) = assertEquals(ids.toList(), diags.ids)

internal fun int(i: Long): IntegerConstantNode = IntegerConstantNode(i, IntegralSuffix.NONE)

internal fun double(f: Double): FloatingConstantNode = FloatingConstantNode(f, FloatingSuffix.NONE)

internal val Keywords.kw get() = Keyword(this)

internal val int = DeclarationSpecifier(typeSpec = IntType(Keywords.INT.kw))
internal val double = DeclarationSpecifier(typeSpec = DoubleTypeSpec(Keywords.DOUBLE.kw))
internal val longLong = DeclarationSpecifier(typeSpec = LongLong(Keywords.LONG.kw))
internal val uLongLong =
    DeclarationSpecifier(typeSpec = UnsignedLongLong(Keywords.UNSIGNED.kw))
internal val longDouble = DeclarationSpecifier(typeSpec = LongDouble(Keywords.LONG.kw))
internal val signedChar = DeclarationSpecifier(typeSpec = SignedChar(Keywords.SIGNED.kw))

internal infix fun ASTNode.assertEquals(rhs: ASTNode) = assertEquals(this, rhs)

internal fun name(s: String): IdentifierNode = IdentifierNode(s).withRange(0..0)
internal fun nameRef(s: String, t: TypeName) = TypedIdentifier(s, t)
internal fun nameDecl(s: String) = NamedDeclarator(name(s), listOf(), emptyList())

internal fun ptr(d: Declarator) =
    NamedDeclarator(d.name, d.indirection + listOf(listOf()), d.suffixes)

internal fun ptr(s: String) = ptr(nameDecl(s))

internal fun String.withPtrs(vararg q: TypeQualifierList) =
    NamedDeclarator(name(this), q.asList(), emptyList())

internal infix fun <T> String.assign(it: T) =
    nameDecl(this) to ExpressionInitializer(parseDSLElement(it))

internal typealias DeclInit = Pair<Declarator, Initializer?>

internal infix fun DeclarationSpecifier.declare(decl: DeclInit) =
    Declaration(this, listOf(decl))

internal infix fun DeclarationSpecifier.declare(decl: Declarator) =
    Declaration(this, listOf(decl to null))

@JvmName("declareDeclarators")
internal infix fun DeclarationSpecifier.declare(list: List<DeclInit>) =
    Declaration(this, list.map { it })

@JvmName("declareStrings")
internal infix fun DeclarationSpecifier.declare(list: List<String>) =
    Declaration(this, list.map { nameDecl(it) to null })

internal infix fun DeclarationSpecifier.proto(decl: Declarator) =
    Declaration(this, listOf(decl to null))

internal infix fun DeclarationSpecifier.proto(s: String) = this proto (s withParams emptyList())

internal infix fun DeclarationSpecifier.func(decl: Declarator) = this to decl

internal infix fun DeclarationSpecifier.func(s: String) = this to (s withParams emptyList())

internal infix fun DeclarationSpecifier.declare(s: String) =
    Declaration(this, listOf(nameDecl(s) to null))

internal infix fun DeclarationSpecifier.declare(sm: List<StructMember>) =
    StructDeclaration(this, sm)

internal infix fun DeclarationSpecifier.param(s: String) = ParameterDeclaration(this, nameDecl(s))

internal fun Declarator.withParams(params: List<ParameterDeclaration>,
                                   variadic: Boolean): Declarator {
  val idents = params.mapTo(mutableListOf()) {
    @Suppress("USELESS_CAST") // Hint, it's not useless
    nameRef(it.declarator.name.name, typeNameOf(it.declSpec, it.declarator)) as OrdinaryIdentifier
  }
  val scope = LexicalScope(idents = idents)
  return NamedDeclarator(name, indirection, suffixes + ParameterTypeList(params, scope, variadic))
}

internal infix fun Declarator.withParams(params: List<ParameterDeclaration>) =
    withParams(params, false)

private fun String.withParams(params: List<ParameterDeclaration>, variadic: Boolean) =
    nameDecl(this).withParams(params, variadic)

internal infix fun String.withParams(params: List<ParameterDeclaration>) = withParams(params, false)
internal infix fun String.withParamsV(params: List<ParameterDeclaration>) = withParams(params, true)

internal infix fun Pair<DeclarationSpecifier, Declarator>.body(s: Statement): FunctionDefinition {
  if (s is ErrorStatement) return FunctionDefinition(first, second, s)
  if (s !is CompoundStatement) throw IllegalArgumentException("Not compound")
  if (!second.isFunction()) throw IllegalArgumentException("Not function")
  val ptl = second.getFunctionTypeList()
  ptl.scope.idents += s.scope.idents
  ptl.scope.labels += s.scope.labels
  ptl.scope.tagNames += s.scope.tagNames
  return FunctionDefinition(first, second, CompoundStatement(s.items, ptl.scope))
}

internal fun ifSt(e: Expression, success: () -> Statement) = IfStatement(e, success(), null)
internal fun ifSt(e: Expression, success: CompoundStatement) = IfStatement(e, success, null)

internal infix fun IfStatement.elseSt(failure: () -> Statement) =
    IfStatement(this.cond, this.success, failure())

internal infix fun IfStatement.elseSt(failure: CompoundStatement) =
    IfStatement(this.cond, this.success, failure)

internal fun returnSt(e: Expression) = ReturnStatement(e)

internal fun <T> compoundOf(vararg elements: T, scope: LexicalScope? = null) =
    listOf(*elements).compound(scope)

internal fun emptyCompound() = CompoundStatement(emptyList(), LexicalScope())

internal fun <T> List<T>.compound(scope: LexicalScope? = null) = CompoundStatement(map {
  when (it) {
    is Statement -> StatementItem(it)
    is Declaration -> DeclarationItem(it)
    is TagSpecifier -> DeclarationItem(Declaration(it.toSpec(), emptyList()))
    else -> throw IllegalArgumentException("Bad type")
  }
}, with(scope ?: LexicalScope()) {
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
  return ForStatement(ForExpressionInitializer(e), cond, cont, loopable)
}

internal fun forSt(e: Declaration,
                   cond: Expression?,
                   cont: Expression?,
                   loopable: Statement): ForStatement {
  return ForStatement(DeclarationInitializer(e), cond, cont, loopable)
}

internal infix fun String.labeled(s: Statement) = LabeledStatement(IdentifierNode(this), s)

internal fun goto(s: String) = GotoStatement(IdentifierNode(s))

internal inline fun <reified T> struct(name: String?, decls: List<T>): StructDefinition {
  val d = decls.map {
    when (T::class) {
      StructDeclaration::class -> it as StructDeclaration
      Declaration::class -> {
        it as Declaration
        StructDeclaration(it.declSpecs, it.declaratorList.map { (first, _) ->
          StructMember(first, null)
        })
      }
      else -> throw IllegalArgumentException("Bad decls in struct")
    }
  }
  return StructDefinition(
      name = name?.let { name(it) }, decls = d, tagKindKeyword = Keywords.STRUCT.kw)
}

internal fun TagSpecifier.toSpec() = DeclarationSpecifier(typeSpec = this)

internal infix fun String.bitSize(expr: Expression) = StructMember(nameDecl(this), expr)
internal infix fun String.bitSize(it: Long) = this bitSize int(it)

internal fun String.typedefBy(ds: DeclarationSpecifier): DeclarationSpecifier {
  if (!ds.isTypedef()) throw IllegalArgumentException("Not typedef")
  val td = TypedefName(ds, nameDecl(this))
  return DeclarationSpecifier(typeSpec = TypedefNameSpecifier(name(this), td))
}

internal operator fun <T> IdentifierNode.get(arraySize: T) =
    NamedDeclarator(this, emptyList(), listOf(ExpressionSize(parseDSLElement(arraySize))))

internal operator fun <T> NamedDeclarator.get(arraySize: T): NamedDeclarator {
  return NamedDeclarator(name, indirection, suffixes + ExpressionSize(parseDSLElement(arraySize)))
}

internal infix fun <LHS, RHS> LHS.add(that: RHS) = this to that with BinaryOperators.ADD
internal infix fun <LHS, RHS> LHS.sub(that: RHS) = this to that with BinaryOperators.SUB
internal infix fun <LHS, RHS> LHS.mul(that: RHS) = this to that with BinaryOperators.MUL
internal infix fun <LHS, RHS> LHS.div(that: RHS) = this to that with BinaryOperators.DIV
internal infix fun <LHS, RHS> LHS.comma(that: RHS) = this to that with BinaryOperators.COMMA

private fun <T> parseDSLElement(it: T): Expression {
  return when (it) {
    is Expression -> it
    is Int -> int(it.toLong())
    is Double -> double(it.toDouble())
    else -> throw IllegalArgumentException("Bad types")
  }
}

internal infix fun <LHS, RHS> Pair<LHS, RHS>.with(op: BinaryOperators): BinaryExpression {
  val lhs = parseDSLElement(first)
  val rhs = parseDSLElement(second)
  return BinaryExpression(op, lhs, rhs)
}

internal operator fun <T> UnaryOperators.get(it: T) = UnaryExpression(this, parseDSLElement(it))

internal operator fun <T : Any> String.invoke(vararg l: T) = name(this)(l)

internal operator fun <Receiver> Receiver.invoke(): FunctionCall {
  return FunctionCall(parseDSLElement(this), emptyList())
}

internal operator fun <Receiver, T : Any> Receiver.invoke(vararg l: T): FunctionCall {
  return FunctionCall(parseDSLElement(this), l.map { parseDSLElement(it) })
}

