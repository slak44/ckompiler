package slak.test

import slak.ckompiler.MachineTargetData
import slak.ckompiler.SourceFileName
import slak.ckompiler.SourcedRange
import slak.ckompiler.lexer.*
import slak.ckompiler.parser.*
import slak.ckompiler.parser.Char
import slak.ckompiler.parser.Short

internal val Keywords.kw get() = Keyword(this)
internal val Punctuators.pct
  get() =
    Punctuator(this).withDebugData("ASTCommonTest", "", 0) as Punctuator

private object ZeroRange : SourcedRange {
  override val expandedName: String? = null
  override val expandedFrom: SourcedRange? = null
  override val sourceFileName: SourceFileName? = javaClass.simpleName
  override val sourceText: String? = ""
  override val range: IntRange = 0..0
}

internal fun <T : ASTNode> T.zeroRange(): T {
  this.setRange(ZeroRange)
  return this
}

internal fun int(i: Long) = IntegerConstantNode(i).zeroRange()
internal fun long(i: Long) = IntegerConstantNode(i, IntegralSuffix.LONG).zeroRange()
internal fun float(f: Double) = FloatingConstantNode(f, FloatingSuffix.FLOAT).zeroRange()
internal fun double(f: Double) = FloatingConstantNode(f, FloatingSuffix.NONE).zeroRange()

internal val const: TypeQualifierList = listOf(Keywords.CONST.kw)

internal val void = DeclarationSpecifier(typeSpec = VoidTypeSpec(Keywords.VOID.kw)).zeroRange()
internal val bool = DeclarationSpecifier(typeSpec = Bool(Keywords.BOOL.kw)).zeroRange()
internal val short = DeclarationSpecifier(typeSpec = Short(Keywords.SHORT.kw)).zeroRange()
internal val int = DeclarationSpecifier(typeSpec = IntType(Keywords.INT.kw)).zeroRange()
internal val uInt = DeclarationSpecifier(typeSpec = UnsignedInt(Keywords.UNSIGNED.kw)).zeroRange()
internal val float =
    DeclarationSpecifier(typeSpec = FloatTypeSpec(Keywords.FLOAT.kw)).zeroRange()
internal val double =
    DeclarationSpecifier(typeSpec = DoubleTypeSpec(Keywords.DOUBLE.kw)).zeroRange()
internal val long = DeclarationSpecifier(typeSpec = LongType(Keywords.LONG.kw)).zeroRange()
internal val longLong = DeclarationSpecifier(typeSpec = LongLong(Keywords.LONG.kw)).zeroRange()
internal val uLongLong =
    DeclarationSpecifier(typeSpec = UnsignedLongLong(Keywords.UNSIGNED.kw)).zeroRange()
internal val longDouble = DeclarationSpecifier(typeSpec = LongDouble(Keywords.LONG.kw)).zeroRange()
internal val signedChar =
    DeclarationSpecifier(typeSpec = SignedChar(Keywords.SIGNED.kw)).zeroRange()
internal val constChar = DeclarationSpecifier(
    typeSpec = Char(Keywords.CHAR.kw),
    typeQualifiers = const
).zeroRange()

internal fun const(type: UnqualifiedTypeName) =
    QualifiedType(type, const, isStorageRegister = false)

internal infix fun ASTNode.assertEquals(rhs: ASTNode) = kotlin.test.assertEquals(this, rhs)

internal fun name(s: String): IdentifierNode = IdentifierNode(s).zeroRange()
internal fun nameRef(s: String, t: TypeName) = TypedIdentifier(s, t).zeroRange()
internal fun FunctionDefinition.toRef() =
    nameRef(name, PointerType(functionType, emptyList(), isStorageRegister = false))

internal fun nameDecl(s: String) =
    NamedDeclarator.base(name(s), emptyList(), emptyList()).zeroRange()

internal fun ptr(d: Declarator) = d.asPtr()

internal fun ptr(s: String) = ptr(nameDecl(s))

internal fun String.withPtrs(vararg q: TypeQualifierList) =
    NamedDeclarator.base(name(this), q.asList(), emptyList()).zeroRange()

internal infix fun <T> String.assign(it: T) =
    nameDecl(this) to ExpressionInitializer(parseDSLElement(it).zeroRange(), Punctuators.ASSIGN.pct)

internal infix fun <T> Declarator.assign(it: T) =
    this to ExpressionInitializer(parseDSLElement(it).zeroRange(), Punctuators.ASSIGN.pct)

internal infix fun <T> TypedIdentifier.assign(it: T) =
    BinaryExpression(BinaryOperators.ASSIGN, this, parseDSLElement(it), type).zeroRange()

internal infix fun <T> TypedIdentifier.plusAssign(it: T) =
    BinaryExpression(BinaryOperators.PLUS_ASSIGN, this, parseDSLElement(it), type).zeroRange()

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
        require(it.first is Declarator) { "Bad decl type" }
        require(it.second is Initializer) { "Bad init type" }
        @Suppress("UNCHECKED_CAST") // It is checked just above
        it as DeclInit
      }
      else -> throw IllegalArgumentException("Bad type")
    }
  }
  return Declaration(this, decls).zeroRange()
}

@JvmName("declareDeclarators")
internal infix fun DeclarationSpecifier.declare(list: List<DeclInit>) =
    Declaration(this, list.map { it })

@JvmName("declareStrings")
internal infix fun DeclarationSpecifier.declare(list: List<String>) =
    Declaration(this, list.map { nameDecl(it) to null }).zeroRange()

internal infix fun DeclarationSpecifier.proto(decl: Declarator) =
    Declaration(this, listOf(decl to null)).zeroRange()

internal infix fun DeclarationSpecifier.proto(s: String) = this proto (s withParams emptyList())

internal infix fun DeclarationSpecifier.func(decl: Declarator) = this to decl

internal infix fun DeclarationSpecifier.func(s: String) = this to (s withParams emptyList())

internal infix fun DeclarationSpecifier.declare(s: String) =
    Declaration(this, listOf(nameDecl(s) to null)).zeroRange()

internal infix fun DeclarationSpecifier.declare(sm: List<StructMember>) =
    StructDeclaration(this, sm).zeroRange()

internal infix fun DeclarationSpecifier.param(s: Declarator) =
    ParameterDeclaration(this, s).zeroRange()

internal infix fun DeclarationSpecifier.param(s: String) =
    ParameterDeclaration(this, nameDecl(s)).zeroRange()

internal infix fun DeclarationSpecifier.withParams(params: List<ParameterDeclaration>) =
    this param AbstractDeclarator.blank().withParams(params)

internal fun DeclarationSpecifier.toParam() =
    this param AbstractDeclarator.blank().zeroRange()

private fun Declarator.addToTier(quals: TypeQualifierList?, suffix: DeclaratorSuffix?): Declarator {
  val lastInds = (indirection.lastOrNull() ?: emptyList()).toMutableList()
  if (quals != null) lastInds += listOf(quals)
  val lastSufs = (suffixes.lastOrNull() ?: emptyList()).toMutableList()
  if (suffix != null) lastSufs += suffix
  val inds = indirection.dropLast(1) + listOf(lastInds)
  val sufs = suffixes.dropLast(1) + listOf(lastSufs)
  return if (this is NamedDeclarator) {
    NamedDeclarator(name, inds, sufs)
  } else {
    AbstractDeclarator(inds, sufs)
  }.zeroRange()
}

private fun Declarator.newTier(quals: TypeQualifierList?, suffix: DeclaratorSuffix?): Declarator {
  val inds =
      if (quals == null) indirection + listOf(listOf()) else indirection + listOf(listOf(quals))
  val sufs = if (suffix == null) suffixes + listOf(listOf()) else suffixes + listOf(listOf(suffix))
  return if (this is NamedDeclarator) {
    NamedDeclarator(name, inds, sufs)
  } else {
    AbstractDeclarator(inds, sufs)
  }.zeroRange()
}

private fun Declarator.asPtr() = addToTier(emptyList(), null)

private fun Declarator.withSuffix(suffix: DeclaratorSuffix) = addToTier(null, suffix)

private fun ptlFrom(params: List<ParameterDeclaration>, variadic: Boolean): ParameterTypeList {
  val idents = params.mapNotNullTo(mutableListOf()) {
    if (it.declarator !is NamedDeclarator) return@mapNotNullTo null
    @Suppress("USELESS_CAST") // Hint, it's not useless
    nameRef(it.declarator.name.name, typeNameOf(it.declSpec, it.declarator)) as OrdinaryIdentifier
  }
  val scope = LexicalScope(idents = idents)
  return ParameterTypeList(params, scope, variadic)
}

internal fun Declarator.withExtraParams(
    params: List<ParameterDeclaration>,
    variadic: Boolean
): Declarator = newTier(null, ptlFrom(params, variadic))

internal infix fun Declarator.withExtraParams(params: List<ParameterDeclaration>) =
    withExtraParams(params, false)

internal fun Declarator.withParams(params: List<ParameterDeclaration>, variadic: Boolean) =
    withSuffix(ptlFrom(params, variadic))

internal infix fun Declarator.withParams(params: List<ParameterDeclaration>) =
    withParams(params, false)

private fun String.withParams(params: List<ParameterDeclaration>, variadic: Boolean) =
    nameDecl(this).withParams(params, variadic)

internal infix fun String.withParams(params: List<ParameterDeclaration>) =
    withParams(params, false) as NamedDeclarator

internal infix fun String.withParamsV(params: List<ParameterDeclaration>) =
    withParams(params, true) as NamedDeclarator

internal infix fun Pair<DeclarationSpecifier, Declarator>.body(s: Statement): FunctionDefinition {
  if (s is ErrorStatement) return FunctionDefinition(first, second, s)
  require(s is CompoundStatement) { "Not compound" }
  require(second.isFunction()) { "Not function" }
  val ptl = second.getFunctionTypeList()
  ptl.scope.idents += s.scope.idents
  ptl.scope.labels += s.scope.labels
  ptl.scope.tagNames += s.scope.tagNames
  return FunctionDefinition(first, second, CompoundStatement(s.items, ptl.scope).zeroRange())
}

internal fun ifSt(e: Expression, success: () -> Statement) = IfStatement(e, success(), null)
internal fun ifSt(e: Expression, success: CompoundStatement) = IfStatement(e, success, null)

internal infix fun IfStatement.elseSt(failure: () -> Statement) =
    IfStatement(this.cond, this.success, failure())

internal infix fun IfStatement.elseSt(failure: CompoundStatement) =
    IfStatement(this.cond, this.success, failure)

internal fun returnSt() = ReturnStatement(null)
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
      is LabeledStatement -> labels += it.label
      is Statement -> {
        // Do nothing intentionally
      }
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
  val scope = LexicalScope()
  if (e is DeclarationInitializer) {
    scope.idents += declsToTypeIdents(e.value)
  }
  return ForStatement(e, cond, cont, loopable, scope)
}

internal fun forSt(e: Expression,
                   cond: Expression?,
                   cont: Expression?,
                   loopable: Statement): ForStatement {
  return ForStatement(ForExpressionInitializer(e.zeroRange()), cond, cont, loopable, LexicalScope())
}

internal fun forSt(e: Declaration,
                   cond: Expression?,
                   cont: Expression?,
                   loopable: Statement): ForStatement {
  val scope = LexicalScope()
  scope.idents += declsToTypeIdents(e)
  return ForStatement(DeclarationInitializer(e.zeroRange()), cond, cont, loopable, scope)
}

internal infix fun String.labeled(s: Statement) = LabeledStatement(name(this), s).zeroRange()

internal fun defaultLabeled(s: Statement) = DefaultStatement(s).zeroRange()

internal fun <T> T.caseLabeled(s: Statement) =
    CaseStatement(parseDSLElement(this) as ExprConstantNode, s).zeroRange()

internal fun <T> switch(onExpr: T, statement: Statement) =
    SwitchStatement(parseDSLElement(onExpr), statement).zeroRange()

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

internal fun typedef(
    ds: DeclarationSpecifier,
    decl: NamedDeclarator
): Pair<DeclarationSpecifier, DeclarationSpecifier> {
  val tdSpec = ds.copy(storageClass = Keywords.TYPEDEF.kw).zeroRange()
  val ref = DeclarationSpecifier(
      typeSpec = TypedefNameSpecifier(decl.name, TypedefName(tdSpec, decl)))
  return tdSpec to ref
}

internal operator fun <T> Expression.get(arraySize: T): Expression {
  val e = parseDSLElement(arraySize)
  require(e is ExprConstantNode)
  require(type is PointerType || type is ArrayType)
  val innerType =
      if (type is PointerType) (type as PointerType).referencedType
      else (type as ArrayType).elementType
  return ArraySubscript(this, e, innerType)
}

internal operator fun <T> IdentifierNode.get(arraySize: T): NamedDeclarator {
  val e = parseDSLElement(arraySize)
  require(e is ExprConstantNode)
  return NamedDeclarator(this, emptyList(), listOf(listOf(ConstantSize(e)))).zeroRange()
}

internal operator fun <T> NamedDeclarator.get(arraySize: T): NamedDeclarator {
  val e = parseDSLElement(arraySize)
  val size = if (e is ExprConstantNode) ConstantSize(e) else ExpressionSize(e)
  return withSuffix(size) as NamedDeclarator
}

internal object NewTier

internal operator fun <T> Declarator.get(arraySize: T, newTier: NewTier): NamedDeclarator {
  newTier === newTier // Suppress was not working
  val e = parseDSLElement(arraySize)
  val size = if (e is ExprConstantNode) ConstantSize(e) else ExpressionSize(e)
  return newTier(null, size) as NamedDeclarator
}

internal operator fun NamedDeclarator.get(noSize: NoSize): NamedDeclarator {
  return withSuffix(noSize) as NamedDeclarator
}

internal operator fun <T> TypedIdentifier.get(it: T): ArraySubscript {
  return ArraySubscript(this, parseDSLElement(it), (this.type as ArrayType).elementType).zeroRange()
}

internal fun prefixInc(e: Expression) =
    IncDecOperation(e, isDecrement = false, isPostfix = false).zeroRange()

internal fun postfixInc(e: Expression) =
    IncDecOperation(e, isDecrement = false, isPostfix = true).zeroRange()

internal infix fun <LHS, RHS> LHS.add(that: RHS) = this to that with BinaryOperators.ADD
internal infix fun <LHS, RHS> LHS.sub(that: RHS) = this to that with BinaryOperators.SUB
internal infix fun <LHS, RHS> LHS.mul(that: RHS) = this to that with BinaryOperators.MUL
internal infix fun <LHS, RHS> LHS.div(that: RHS) = this to that with BinaryOperators.DIV
internal infix fun <LHS, RHS> LHS.comma(that: RHS) = this to that with BinaryOperators.COMMA
internal infix fun <LHS, RHS> LHS.equals(that: RHS) = this to that with BinaryOperators.EQ
internal infix fun <LHS, RHS> LHS.assign(that: RHS) = this to that with BinaryOperators.ASSIGN
internal infix fun <LHS, RHS> LHS.less(that: RHS) = this to that with BinaryOperators.LT
internal infix fun <LHS, RHS> LHS.land(that: RHS) = this to that with BinaryOperators.AND
internal infix fun <LHS, RHS> LHS.lor(that: RHS) = this to that with BinaryOperators.OR

internal fun <T1, T2, T3> T1.qmark(success: T2, failure: T3) = TernaryConditional(
    parseDSLElement(this),
    parseDSLElement(success),
    parseDSLElement(failure)
).zeroRange()

internal fun strLit(string: String) = StringLiteralNode(string, StringEncoding.CHAR).zeroRange()

private fun <T> parseDSLElement(it: T): Expression {
  return when (it) {
    is Expression -> it
    is Int -> int(it.toLong())
    is Double -> double(it.toDouble())
    is FunctionDefinition -> it.toRef()
    is Declaration -> throw IllegalArgumentException("Bad types (did you mean to use a nameRef?)")
    else -> throw IllegalArgumentException("Bad types")
  }
}

internal infix fun <LHS, RHS> Pair<LHS, RHS>.with(op: BinaryOperators): BinaryExpression {
  val lhs = parseDSLElement(first)
  val rhs = parseDSLElement(second)
  val (exprType, _) = op.applyTo(lhs.type, rhs.type)
  return BinaryExpression(op, lhs, rhs, exprType).zeroRange()
}

internal fun <T> sizeOf(it: T) =
    SizeofTypeName(parseDSLElement(it).type, MachineTargetData.x64.sizeType).zeroRange()

internal fun sizeOf(it: TypeName) = SizeofTypeName(it, MachineTargetData.x64.sizeType).zeroRange()

internal fun <T> TypeName.cast(it: T) = CastExpression(parseDSLElement(it), this).zeroRange()

internal operator fun <T> UnaryOperators.get(it: T): UnaryExpression {
  val operand = parseDSLElement(it)
  val realOperand = if (this == UnaryOperators.REF && operand is TypedIdentifier) {
    TypedIdentifier(operand.name, operand.type.normalize())
  } else {
    operand
  }
  return UnaryExpression(this, realOperand, applyTo(realOperand.type)).zeroRange()
}

internal operator fun <T : Any> String.invoke(vararg l: T) = name(this)(l).zeroRange()

internal operator fun FunctionDefinition.invoke() = FunctionCall(toRef(), emptyList()).zeroRange()

internal operator fun <T : Any> FunctionDefinition.invoke(vararg l: T): FunctionCall {
  return FunctionCall(toRef(), l.map { parseDSLElement(it) }).zeroRange()
}

internal operator fun <Receiver> Receiver.invoke(): FunctionCall {
  return FunctionCall(parseDSLElement(this), emptyList()).zeroRange()
}

internal operator fun <Receiver, T : Any> Receiver.invoke(vararg l: T): FunctionCall {
  return FunctionCall(parseDSLElement(this), l.map { parseDSLElement(it) }).zeroRange()
}

internal fun fnPtrOf(d: Declaration): TypedIdentifier {
  require(d.declaratorList.size == 1)
  require(d.declaratorList[0].first is NamedDeclarator)
  require(d.declaratorList[0].first.isFunction())
  val fnType = typeNameOf(d.declSpecs, d.declaratorList[0].first)
  require(fnType is FunctionType)
  return nameRef(d.declaratorList[0].first.name.name, PointerType(fnType, emptyList(), fnType))
}
