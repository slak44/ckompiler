package slak.ckompiler.analysis

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.MachineTargetData
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

/**
 * @param registerIds per-function virtual register counter
 */
private class IRBuilderContext(
    val machineTargetData: MachineTargetData,
    private val registerIds: IdCounter,
    val stackVariableIds: MutableSet<AtomicId>,
    val irValueFactory: IRValueFactory
) {
  val instructions = mutableListOf<IRInstruction>()

  fun newRegister(type: TypeName) = VirtualRegister(registerIds(), type)
}

/**
 * C standard: 6.5.3.3, 6.5.3.2
 */
private fun IRBuilderContext.buildUnary(expr: UnaryExpression): IRInstruction = when (expr.op) {
  UnaryOperators.DEREF -> {
    val ptr = buildOperand(expr.operand)
    LoadMemory(newRegister((ptr.type as PointerType).referencedType), ptr)
  }
  UnaryOperators.NOT -> {
    require(expr.operand.type.isScalar())
    val res = newRegister(expr.type)
    val operand = buildOperand(expr.operand)
    when (expr.operand.type) {
      is IntegralType, is PointerType -> {
        IntCmp(res, irValueFactory.getIntConstant(0, expr.operand.type), operand, Comparisons.EQUAL)
      }
      is FloatingType -> {
        FltCmp(res, FltConstant(0.0, expr.operand.type), operand, Comparisons.EQUAL)
      }
      else -> logger.throwICE("Impossible branch, checked above")
    }
  }
  UnaryOperators.BIT_NOT -> {
    require(expr.operand.type.unqualify() is IntegralType)
    IntInvert(newRegister(expr.type), buildOperand(expr.operand))
  }
  UnaryOperators.MINUS -> {
    require(expr.operand.type.unqualify() is ArithmeticType)
    when (expr.operand.type) {
      is IntegralType -> IntNeg(newRegister(expr.type), buildOperand(expr.operand))
      is FloatingType -> FltNeg(newRegister(expr.type), buildOperand(expr.operand))
      else -> logger.throwICE("Impossible branch, checked above")
    }
  }
  UnaryOperators.REF, UnaryOperators.PLUS -> logger.throwICE("Impossible branch, checked in caller")
}

private fun IRBuilderContext.buildBinaryOperands(
    expr: BinaryExpression,
): Triple<VirtualRegister, IRValue, IRValue> {
  val result = expr.op.applyTo(expr.lhs.type, expr.rhs.type)
  require(result.exprType !is ErrorType) {
    "Binary operands have invalid types for IR generation"
  }
  return Triple(newRegister(result.exprType), buildOperand(expr.lhs), buildOperand(expr.rhs))
}

/**
 * Deal with common integer and float binary operations: + - * /
 */
private fun IRBuilderContext.buildCommonBinary(expr: BinaryExpression): IRInstruction {
  val (reg, lhs, rhs) = buildBinaryOperands(expr)
  return when (expr.lhs.type) {
    is IntegralType, is PointerType -> {
      val op = when (expr.op) {
        BinaryOperators.ADD -> IntegralBinaryOps.ADD
        BinaryOperators.SUB -> IntegralBinaryOps.SUB
        BinaryOperators.MUL -> IntegralBinaryOps.MUL
        BinaryOperators.DIV -> IntegralBinaryOps.DIV
        else -> logger.throwICE("Not a common int/flt binary operation") { expr.op }
      }
      IntBinary(reg, op, lhs, rhs)
    }
    is FloatingType -> {
      val op = when (expr.op) {
        BinaryOperators.ADD -> FloatingBinaryOps.ADD
        BinaryOperators.SUB -> FloatingBinaryOps.SUB
        BinaryOperators.MUL -> FloatingBinaryOps.MUL
        BinaryOperators.DIV -> FloatingBinaryOps.DIV
        else -> logger.throwICE("Not a common int/flt binary operation") { expr.op }
      }
      FltBinary(reg, op, lhs, rhs)
    }
    else -> logger.throwICE("Impossible branch, checked above")
  }
}

private fun IRBuilderContext.buildBinary(expr: BinaryExpression): IRInstruction = when (expr.op) {
  BinaryOperators.ADD, BinaryOperators.SUB, BinaryOperators.DIV, BinaryOperators.MUL -> {
    buildCommonBinary(expr)
  }
  BinaryOperators.LT, BinaryOperators.GT,
  BinaryOperators.LEQ, BinaryOperators.GEQ,
  BinaryOperators.EQ, BinaryOperators.NEQ,
  -> {
    val (reg, lhs, rhs) = buildBinaryOperands(expr)
    when (expr.lhs.type) {
      is IntegralType -> IntCmp(reg, lhs, rhs, Comparisons.from(expr.op))
      is FloatingType -> FltCmp(reg, lhs, rhs, Comparisons.from(expr.op))
      else -> logger.throwICE("Impossible branch, checked above")
    }
  }
  BinaryOperators.MOD, BinaryOperators.LSH, BinaryOperators.RSH,
  BinaryOperators.BIT_AND, BinaryOperators.BIT_XOR, BinaryOperators.BIT_OR,
  -> {
    require(expr.lhs.type.unqualify() is IntegralType)
    val op = when (expr.op) {
      BinaryOperators.MOD -> IntegralBinaryOps.REM
      BinaryOperators.LSH -> IntegralBinaryOps.LSH
      BinaryOperators.RSH -> IntegralBinaryOps.RSH
      BinaryOperators.BIT_AND -> IntegralBinaryOps.AND
      BinaryOperators.BIT_XOR -> IntegralBinaryOps.XOR
      BinaryOperators.BIT_OR -> IntegralBinaryOps.OR
      else -> logger.throwICE("Logically impossible branch, checked above")
    }
    val (reg, lhs, rhs) = buildBinaryOperands(expr)
    IntBinary(reg, op, lhs, rhs)
  }
  BinaryOperators.ASSIGN -> logger.throwICE("ASSIGN is handled by buildOperand")
  BinaryOperators.MUL_ASSIGN, BinaryOperators.DIV_ASSIGN, BinaryOperators.MOD_ASSIGN,
  BinaryOperators.PLUS_ASSIGN, BinaryOperators.SUB_ASSIGN, BinaryOperators.LSH_ASSIGN,
  BinaryOperators.RSH_ASSIGN, BinaryOperators.AND_ASSIGN, BinaryOperators.XOR_ASSIGN,
  BinaryOperators.OR_ASSIGN,
  -> {
    logger.throwICE("Compound assignments must be removed by sequentialize")
  }
  BinaryOperators.AND, BinaryOperators.OR -> {
    logger.throwICE("Logical AND/OR must be removed by sequentialize")
  }
  BinaryOperators.COMMA -> {
    logger.throwICE("Commas must be removed by sequentialize")
  }
}

private fun IRBuilderContext.buildFunctionCall(expr: FunctionCall): IRInstruction {
  val args = expr.args.map(this::buildOperand)
  val funType = expr.calledExpr.type.unqualify().asCallable()!!
  val res = newRegister(funType.returnType)
  return if (expr.calledExpr is TypedIdentifier) {
    NamedCall(res, NamedConstant(expr.calledExpr.name, funType), args)
  } else {
    val called = buildOperand(expr.calledExpr)
    require(called is VirtualRegister)
    IndirectCall(res, called, args)
  }
}

/**
 * Returns a pointer to the LVALUE expression of the argument.
 *
 * C standard: 6.5.3.2
 */
private fun IRBuilderContext.buildLValuePtr(lvalue: Expression): IRValue = when (lvalue) {
  is TypedIdentifier -> {
    val sv = StackVariable(lvalue)
    stackVariableIds += sv.id
    instructions += StoreMemory(MemoryLocation(sv), Variable(lvalue))
    sv
  }
  is ArraySubscript -> buildPtrOffset(
      buildOperand(lvalue.subscripted),
      buildOperand(lvalue.subscript),
      PointerType(lvalue.type, emptyList())
  )
  is MemberAccessExpression -> buildMemberPtrAccess(lvalue)
  else -> logger.throwICE("Unhandled lvalue")
}

private fun IRBuilderContext.buildAssignment(lhs: Expression, rhs: IRValue): IRInstruction {
  require(lhs.valueType != ValueType.RVALUE)
  return when (lhs) {
    is TypedIdentifier -> {
      MoveInstr(valueFromTid(lhs), rhs)
    }
    is UnaryExpression -> {
      require(lhs.op == UnaryOperators.DEREF)
      // For dereferences, the operand is guaranteed to be a pointer
      StoreMemory(buildOperand(lhs.operand), rhs)
    }
    else -> {
      StoreMemory(buildLValuePtr(lhs), rhs)
    }
  }
}

/**
 * Deal with pointer arithmetic. Returns ([resPtrType]*) ([base] + [offset]).
 */
private fun IRBuilderContext.buildPtrOffset(
    base: IRValue,
    offset: IRValue,
    resPtrType: TypeName,
): IRValue {
  require(resPtrType is PointerType)
  val baseType = base.type.unqualify().normalize()
  require(baseType is PointerType)
  val offsetTo = IntBinary(newRegister(baseType), IntegralBinaryOps.ADD, base, offset)
  instructions += offsetTo
  // Avoid unnecessary cast if possible
  if (resPtrType == offsetTo.result.type) return offsetTo.result
  val typedPtr = ReinterpretCast(newRegister(resPtrType), offsetTo.result)
  instructions += typedPtr
  return typedPtr.result
}

/**
 * Dereference the result of [buildPtrOffset].
 */
private fun IRBuilderContext.buildOffset(
    base: IRValue,
    offset: IRValue,
    resType: TypeName,
): LoadableValue {
  val offsetPtr = buildPtrOffset(base, offset, PointerType(resType, emptyList()))
  val dereferencePtr = LoadMemory(newRegister(resType), offsetPtr)
  instructions += dereferencePtr
  return dereferencePtr.result
}

/**
 * Get a pointer to the tag from the [MemberAccessExpression], and the type of the tag.
 */
private fun IRBuilderContext.buildTagPtr(expr: MemberAccessExpression): Pair<IRValue, TagType> {
  val lhsType = expr.target.type.unqualify()
  return if (lhsType is PointerType) {
    require(lhsType.referencedType is TagType)
    check(expr.accessOperator.pct == Punctuators.ARROW)
    buildOperand(expr.target) to lhsType.referencedType
  } else {
    require(lhsType is TagType)
    check(expr.accessOperator.pct == Punctuators.DOT)
    val addrOf = buildLValuePtr(expr.target)
    addrOf to lhsType
  }
}

/**
 * Returns a pointer to the targeted member.
 */
private fun IRBuilderContext.buildMemberPtrAccess(expr: MemberAccessExpression): IRValue {
  val (basePtr, tagType) = buildTagPtr(expr)
  val memberPtrType = PointerType(expr.type, emptyList())
  return when (tagType) {
    is StructureType -> {
      val offset = machineTargetData.offsetOf(tagType, expr.memberName)
      val offsetCt = irValueFactory.getIntConstant(offset, machineTargetData.ptrDiffType)
      buildPtrOffset(basePtr, offsetCt, memberPtrType)
    }
    is UnionType -> {
      val cast = ReinterpretCast(newRegister(memberPtrType), basePtr)
      instructions += cast
      cast.result
    }
  }
}

/**
 * C standard: 6.5.3.3.0.2, note 104
 */
private fun IRBuilderContext.buildOperand(expr: Expression): IRValue = when (expr) {
  is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
  is TernaryConditional -> logger.throwICE("TernaryConditional was removed")
  is SizeofTypeName -> logger.throwICE("SizeofTypeName was removed")
  is VoidExpression -> logger.throwICE("VoidExpression was removed")
  is IncDecOperation -> logger.throwICE("IncDecOperation was removed")
  is UnaryExpression -> when (expr.op) {
    // Get rid of this no-op
    UnaryOperators.PLUS -> buildOperand(expr.operand)
    // This is much easier to deal with directly
    UnaryOperators.REF -> buildLValuePtr(expr.operand)
    else -> {
      val unary = buildUnary(expr)
      instructions += unary
      unary.result
    }
  }
  is BinaryExpression -> {
    if (expr.op == BinaryOperators.ASSIGN) {
      val rhs = buildOperand(CastExpression(expr.rhs, expr.type))
      val assign = buildAssignment(expr.lhs, rhs)
      instructions += assign
      // Stores are hoisted by sequentialize, so this register will never be used
      VirtualRegister(0, VoidType)
    } else {
      val binary = buildBinary(expr)
      instructions += binary
      binary.result
    }
  }
  is CastExpression -> {
    // Casts don't give lvalues, thus casting to the unqualified version is perfect
    val castTo = expr.type.unqualify().normalize()
    if (expr.target.type.unqualify().normalize().isCompatibleWith(castTo)) {
      // If this cast doesn't actually change the underlying type, don't even do it at all
      buildOperand(expr.target)
    } else {
      val cast = StructuralCast(newRegister(castTo), buildOperand(expr.target))
      instructions += cast
      cast.result
    }
  }
  is FunctionCall -> {
    val functionCall = buildFunctionCall(expr)
    instructions += functionCall
    functionCall.result
  }
  is MemberAccessExpression -> {
    val ptr = buildMemberPtrAccess(expr)
    val load = LoadMemory(newRegister(expr.type), ptr)
    instructions += load
    load.result
  }
  is ArraySubscript -> {
    buildOffset(buildOperand(expr.subscripted), buildOperand(expr.subscript), expr.type)
  }
  is TypedIdentifier -> valueFromTid(expr)
  is IntegerConstantNode -> irValueFactory.getIntConstant(expr)
  is CharacterConstantNode -> irValueFactory.getIntConstant(expr)
  is FloatingConstantNode -> FltConstant(expr)
  is StringLiteralNode -> StrConstant(expr)
}

private fun IRBuilderContext.valueFromTid(tid: TypedIdentifier): AllocatableValue {
  return if (tid.id in stackVariableIds) {
    val register = newRegister(tid.type)
    instructions += LoadMemory(register, MemoryLocation(StackVariable(tid)))
    register
  } else {
    Variable(tid)
  }
}

/**
 * Transforms top-level expressions passed through [sequentialize] to a list of instructions.
 */
fun createInstructions(
    exprs: List<Expression>,
    targetData: MachineTargetData,
    registerIds: IdCounter,
    stackVariableIds: MutableSet<AtomicId>,
    irValueFactory: IRValueFactory
): List<IRInstruction> {
  val builder = IRBuilderContext(targetData, registerIds, stackVariableIds, irValueFactory)
  for (expr in exprs) {
    val folded = targetData.doConstantFolding(expr)
    val lastValue = builder.buildOperand(folded)
    if (builder.instructions.lastOrNull() !is MoveInstr && lastValue !is VirtualRegister) {
      builder.instructions += MoveInstr(builder.newRegister(lastValue.type), lastValue)
    }
  }
  return builder.instructions
}
