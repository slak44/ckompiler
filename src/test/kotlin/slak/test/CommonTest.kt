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

internal fun prepareCode(s: String, source: SourceFileName): Parser {
  val incs = IncludePaths(emptyList(),
      listOf(IncludePaths.resource("headers/system")),
      listOf(IncludePaths.resource("headers/users")))
  val pp = Preprocessor(s, source, includePaths = incs + IncludePaths.defaultPaths)
  pp.assertNoDiagnostics()
  return Parser(pp.tokens, source, s)
}

internal fun prepareCFG(s: String, source: SourceFileName, convertToSSA: Boolean = true): CFG {
  val p = prepareCode(s, source)
  p.assertNoDiagnostics()
  return CFG(p.root.decls.firstFun(), source, s, convertToSSA = convertToSSA)
}

internal fun prepareCFG(file: File, source: SourceFileName, convertToSSA: Boolean = true): CFG {
  return prepareCFG(file.readText(), source, convertToSSA)
}

internal fun List<ExternalDeclaration>.firstFun(): FunctionDefinition =
    first { it is FunctionDefinition } as FunctionDefinition

internal val List<Diagnostic>.ids get() = map { it.id }

internal fun Parser.assertDiags(vararg ids: DiagnosticId) = assertEquals(ids.toList(), diags.ids)
internal fun IDebugHandler.assertDiags(vararg ids: DiagnosticId) =
    assertEquals(ids.toList(), diags.ids)

fun assertPPDiagnostic(s: String, source: SourceFileName, vararg ids: DiagnosticId) {
  val diagnostics = Preprocessor(s, source).diags
  assertEquals(ids.toList(), diagnostics.ids)
}

internal fun int(i: Long): IntegerConstantNode = IntegerConstantNode(i, IntegralSuffix.NONE)

internal fun double(f: Double): FloatingConstantNode = FloatingConstantNode(f, FloatingSuffix.NONE)

internal val Keywords.kw get() = Keyword(this)

fun <T : ASTNode> T.zeroRange(): T {
  this.setRange(0..0)
  return this
}

internal val int = DeclarationSpecifier(typeSpec = IntType(Keywords.INT.kw)).zeroRange()
internal val uInt = DeclarationSpecifier(typeSpec = UnsignedInt(Keywords.UNSIGNED.kw)).zeroRange()
internal val double =
    DeclarationSpecifier(typeSpec = DoubleTypeSpec(Keywords.DOUBLE.kw)).zeroRange()
internal val longLong = DeclarationSpecifier(typeSpec = LongLong(Keywords.LONG.kw)).zeroRange()
internal val uLongLong =
    DeclarationSpecifier(typeSpec = UnsignedLongLong(Keywords.UNSIGNED.kw)).zeroRange()
internal val longDouble = DeclarationSpecifier(typeSpec = LongDouble(Keywords.LONG.kw)).zeroRange()
internal val signedChar =
    DeclarationSpecifier(typeSpec = SignedChar(Keywords.SIGNED.kw)).zeroRange()

internal infix fun ASTNode.assertEquals(rhs: ASTNode) = assertEquals(this, rhs)

internal fun name(s: String): IdentifierNode = IdentifierNode(s).zeroRange()
internal fun nameRef(s: String, t: TypeName) = TypedIdentifier(s, t).zeroRange()
internal fun FunctionDefinition.toRef() = funcIdent
internal fun nameDecl(s: String) = NamedDeclarator(name(s), listOf(), emptyList())

internal fun ptr(d: Declarator) =
    NamedDeclarator(d.name, d.indirection + listOf(listOf()), d.suffixes)

internal fun ptr(s: String) = ptr(nameDecl(s))

internal fun String.withPtrs(vararg q: TypeQualifierList) =
    NamedDeclarator(name(this), q.asList(), emptyList())

internal infix fun <T> String.assign(it: T) =
    nameDecl(this) to ExpressionInitializer(parseDSLElement(it))

internal infix fun <T> Declarator.assign(it: T) =
    this to ExpressionInitializer(parseDSLElement(it))

internal infix fun <T> TypedIdentifier.assign(it: T) =
    BinaryExpression(BinaryOperators.ASSIGN, this, parseDSLElement(it))

internal typealias DeclInit = Pair<Declarator, Initializer?>

internal infix fun DeclarationSpecifier.declare(decl: DeclInit) =
    Declaration(this, listOf(decl))

internal infix fun DeclarationSpecifier.declare(decl: Declarator) =
    Declaration(this, listOf(decl to null))

internal inline infix fun <reified T : Any>
    DeclarationSpecifier.declare(list: List<T>): Declaration {
  if (list.isEmpty()) return Declaration(this, emptyList())
  val decls = list.map {
    when (it) {
      is String -> nameDecl(it) to null
      is Declarator -> (it as Declarator) to null
      is Pair<*, *> -> {
        if (it.first !is Declarator) throw IllegalArgumentException("Bad decl type")
        if (it.second !is Initializer) throw IllegalArgumentException("Bad init type")
        @Suppress("UNCHECKED_CAST") // It is checked just above
        it as DeclInit
      }
      else -> throw IllegalArgumentException("Bad type")
    }
  }
  return Declaration(this, decls)
}

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

internal infix fun DeclarationSpecifier.param(s: AbstractDeclarator) =
    ParameterDeclaration(this, s).zeroRange()
internal infix fun DeclarationSpecifier.param(s: String) =
    ParameterDeclaration(this, nameDecl(s)).zeroRange()

internal fun DeclarationSpecifier.toParam() =
    this param AbstractDeclarator(emptyList(), emptyList())

internal fun Declarator.withParams(params: List<ParameterDeclaration>,
                                   variadic: Boolean): NamedDeclarator {
  val idents = params.mapNotNullTo(mutableListOf()) {
    if (it.declarator !is NamedDeclarator) return@mapNotNullTo null
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

internal fun <T> returnSt(e: T) = ReturnStatement(parseDSLElement(e))

private fun declsToTypeIdents(d: Declaration): List<TypedIdentifier> {
  return d.declaratorList.map { (decl) -> TypedIdentifier(d.declSpecs, decl as NamedDeclarator) }
}

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
      is Declaration -> idents += declsToTypeIdents(it)
      is ForStatement -> {
        val names = (it.init as? DeclarationInitializer)?.value?.let(::declsToTypeIdents)
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
  return ForStatement(ForExpressionInitializer(e.zeroRange()), cond, cont, loopable)
}

internal fun forSt(e: Declaration,
                   cond: Expression?,
                   cont: Expression?,
                   loopable: Statement): ForStatement {
  return ForStatement(DeclarationInitializer(e.zeroRange()), cond, cont, loopable)
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

internal fun typedef(ds: DeclarationSpecifier,
                     decl: NamedDeclarator): Pair<DeclarationSpecifier, DeclarationSpecifier> {
  val tdSpec = ds.copy(storageClass = Keywords.TYPEDEF.kw)
  val ref = DeclarationSpecifier(
      typeSpec = TypedefNameSpecifier(decl.name, TypedefName(tdSpec, decl)))
  return tdSpec to ref
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
    is Declaration -> throw IllegalArgumentException("Bad types (did you mean to use a nameRef?)")
    else -> throw IllegalArgumentException("Bad types")
  }
}

internal infix fun <LHS, RHS> Pair<LHS, RHS>.with(op: BinaryOperators): BinaryExpression {
  val lhs = parseDSLElement(first)
  val rhs = parseDSLElement(second)
  return BinaryExpression(op, lhs, rhs).zeroRange()
}

internal fun sizeOf(e: Expression) = SizeofExpression(e)

internal operator fun <T> UnaryOperators.get(it: T) = UnaryExpression(this, parseDSLElement(it))

internal operator fun <T : Any> String.invoke(vararg l: T) = name(this)(l)

internal operator fun <Receiver> Receiver.invoke(): FunctionCall {
  return FunctionCall(parseDSLElement(this), emptyList())
}

internal operator fun <Receiver, T : Any> Receiver.invoke(vararg l: T): FunctionCall {
  return FunctionCall(parseDSLElement(this), l.map { parseDSLElement(it) })
}

