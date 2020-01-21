package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.MachineTargetData
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

/**
 * @param registerIds per-function virtual register counter
 */
private class IRBuilderContext(
    val machineTargetData: MachineTargetData,
    private val registerIds: IdCounter
) {
  val instructions = mutableListOf<IRInstruction>()

  fun newRegister(type: TypeName) = VirtualRegister(registerIds(), type)
}

private fun IRBuilderContext.buildCast(expr: CastExpression): ResultInstruction {
  return StructuralCast(newRegister(expr.type), expr.type, buildOperand(expr.target))
}

/**
 * C standard: 6.5.3.3, 6.5.3.2
 */
private fun IRBuilderContext.buildUnary(expr: UnaryExpression): ResultInstruction = when (expr.op) {
  UnaryOperators.DEREF -> {
    val ptr = buildOperand(expr.operand)
    require(ptr.type.unqualify() is PointerType)
    LoadInstr(newRegister(ptr.type), ptr)
  }
  UnaryOperators.NOT -> {
    require(expr.operand.type.isScalar())
    val res = newRegister(expr.type)
    val operand = buildOperand(expr.operand)
    when (expr.operand.type) {
      is IntegralType, is PointerType -> {
        IntCmp(res, IntConstant(0, expr.operand.type), operand, Comparisons.EQUAL)
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
  UnaryOperators.REF,
  UnaryOperators.PLUS -> logger.throwICE("Impossible branch, checked in caller")
}

private fun IRBuilderContext.buildBinaryOperands(
    expr: BinaryExpression
): Triple<VirtualRegister, IRValue, IRValue> {
  require(expr.lhs.type.unqualify() is ArithmeticType && expr.rhs.type == expr.lhs.type)
  return Triple(newRegister(expr.type), buildOperand(expr.lhs), buildOperand(expr.rhs))
}

/**
 * Deal with common integer and float binary operations: + - * /
 */
private fun IRBuilderContext.buildCommonBinary(expr: BinaryExpression): ResultInstruction {
  val (reg, lhs, rhs) = buildBinaryOperands(expr)
  return when (expr.lhs.type) {
    is IntegralType -> {
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

private fun IRBuilderContext.buildBinary(
    expr: BinaryExpression
): ResultInstruction = when (expr.op) {
  BinaryOperators.ADD, BinaryOperators.SUB, BinaryOperators.DIV, BinaryOperators.MUL -> {
    buildCommonBinary(expr)
  }
  BinaryOperators.LT, BinaryOperators.GT, BinaryOperators.LEQ, BinaryOperators.GEQ,
  BinaryOperators.EQ, BinaryOperators.NEQ -> {
    val (reg, lhs, rhs) = buildBinaryOperands(expr)
    when (expr.lhs.type) {
      is IntegralType -> IntCmp(reg, lhs, rhs, Comparisons.from(expr.op))
      is FloatingType -> FltCmp(reg, lhs, rhs, Comparisons.from(expr.op))
      else -> logger.throwICE("Impossible branch, checked above")
    }
  }
  BinaryOperators.MOD, BinaryOperators.LSH, BinaryOperators.RSH,
  BinaryOperators.BIT_AND, BinaryOperators.BIT_XOR, BinaryOperators.BIT_OR -> {
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
  BinaryOperators.OR_ASSIGN -> {
    logger.throwICE("Compound assignments must be removed by sequentialize")
  }
  BinaryOperators.AND, BinaryOperators.OR -> {
    logger.throwICE("Logical AND/OR must be removed by sequentialize")
  }
  BinaryOperators.COMMA -> {
    logger.throwICE("Commas must be removed by sequentialize")
  }
}

private fun IRBuilderContext.buildFunctionCall(expr: FunctionCall): ResultInstruction {
  val args = expr.args.map(this::buildOperand)
  val funType = expr.calledExpr.type.unqualify().asCallable()!!
  val res = newRegister(funType.returnType)
  return if (expr.calledExpr is TypedIdentifier) {
    NamedCall(res, expr.calledExpr.name, funType, args)
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
private fun IRBuilderContext.buildLvaluePtr(lvalue: Expression): IRValue = when (lvalue) {
  is TypedIdentifier -> Variable(lvalue).asPointer()
  is ArraySubscript -> buildPtrOffset(
      buildOperand(lvalue.subscripted),
      buildOperand(lvalue.subscript),
      PointerType(lvalue.type, emptyList())
  )
  is MemberAccessExpression -> buildMemberPtrAccess(lvalue)
  else -> logger.throwICE("Unhandled lvalue")
}

private fun IRBuilderContext.buildAssignment(expr: BinaryExpression) {
  val value = buildOperand(expr.rhs)
  require(expr.lhs.valueType != Expression.ValueType.RVALUE)
  val storeTarget = when (expr.lhs) {
    is TypedIdentifier -> Variable(expr.lhs)
    is UnaryExpression -> {
      require(expr.lhs.op == UnaryOperators.DEREF)
      buildOperand(expr.lhs)
    }
    else -> buildLvaluePtr(expr.lhs)
  }
  instructions += StoreInstr(storeTarget, value)
}

private fun IRBuilderContext.buildPtrOffset(
    base: IRValue,
    offset: IRValue,
    resPtrType: TypeName
): VirtualRegister {
  require(resPtrType is PointerType)
  val baseType = base.type.unqualify().normalize()
  require(baseType is PointerType)
  val offsetTo = IntBinary(newRegister(baseType), IntegralBinaryOps.ADD, base, offset)
  instructions += offsetTo
  val typedPtr = ReinterpretCast(newRegister(resPtrType), resPtrType, offsetTo.result)
  instructions += typedPtr
  return typedPtr.result
}

private fun IRBuilderContext.buildOffset(
    base: IRValue,
    offset: IRValue,
    resType: TypeName
): VirtualRegister {
  val offsetPtr = buildPtrOffset(base, offset, PointerType(resType, emptyList()))
  val deref = LoadInstr(newRegister(resType), offsetPtr)
  instructions += deref
  return deref.result
}

/**
 * Returns a register with a pointer to the target member.
 */
private fun IRBuilderContext.buildMemberPtrAccess(expr: MemberAccessExpression): VirtualRegister {
  val unqualType = expr.target.type.unqualify()
  val (base, tagType) = if (unqualType is PointerType) {
    require(unqualType.referencedType is TagType)
    check(expr.accessOperator.pct == Punctuators.ARROW)
    buildOperand(expr.target) to unqualType.referencedType
  } else {
    require(unqualType is TagType)
    check(expr.accessOperator.pct == Punctuators.DOT)
    val addrOf = buildLvaluePtr(expr.target)
    addrOf to unqualType
  }
  return when (tagType) {
    is StructureType -> {
      val offset = machineTargetData.offsetOf(tagType, expr.memberName)
      val offsetCt = IntConstant(offset.toLong(), machineTargetData.ptrDiffType)
      val memberPtrType = PointerType(expr.type, emptyList())
      buildPtrOffset(base, offsetCt, memberPtrType)
    }
    is UnionType -> {
      val ptrToType = PointerType(expr.type, emptyList())
      val cast = ReinterpretCast(newRegister(ptrToType), ptrToType, base)
      instructions += cast
      cast.result
    }
  }
}

private fun IRBuilderContext.buildMemberAccess(expr: MemberAccessExpression): VirtualRegister {
  val unqualType = expr.target.type.unqualify()
  val (base, tagType) = if (unqualType is PointerType) {
    require(unqualType.referencedType is TagType)
    check(expr.accessOperator.pct == Punctuators.ARROW)
    buildOperand(expr.target) to unqualType.referencedType
  } else {
    require(unqualType is TagType)
    check(expr.accessOperator.pct == Punctuators.DOT)
    when (unqualType) {
      is StructureType -> buildLvaluePtr(expr.target) to unqualType
      is UnionType -> buildOperand(expr.target) to unqualType
    }
  }
  return when (tagType) {
    is StructureType -> {
      val offset = machineTargetData.offsetOf(tagType, expr.memberName)
      buildOffset(base, IntConstant(offset.toLong(), machineTargetData.ptrDiffType), expr.type)
    }
    is UnionType -> {
      val cast = ReinterpretCast(newRegister(expr.type), expr.type, base)
      instructions += cast
      cast.result
    }
  }
}

/**
 * C standard: 6.5.3.3.0.2
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
    UnaryOperators.REF -> buildLvaluePtr(expr.operand)
    else -> {
      val unary = buildUnary(expr)
      instructions += unary
      unary.result
    }
  }
  is BinaryExpression -> {
    if (expr.op == BinaryOperators.ASSIGN) {
      buildAssignment(expr)
      // Stores are hoisted by sequentialize, so this register will never be used
      VirtualRegister(0, VoidType)
    } else {
      val binary = buildBinary(expr)
      instructions += binary
      binary.result
    }
  }
  is CastExpression -> {
    val cast = buildCast(expr)
    instructions += cast
    cast.result
  }
  is FunctionCall -> {
    val functionCall = buildFunctionCall(expr)
    instructions += functionCall
    functionCall.result
  }
  is MemberAccessExpression -> buildMemberAccess(expr)
  is ArraySubscript -> {
    buildOffset(buildOperand(expr.subscripted), buildOperand(expr.subscript), expr.type)
  }
  is TypedIdentifier -> {
    // FIXME: keep this here and remove it for register-stored vars, or insert code for spills?
//    val load = LoadInstr(newRegister(expr.type), Variable(expr))
//    instructions += load
//    load.result
    Variable(expr)
  }
  is IntegerConstantNode -> IntConstant(expr)
  is CharacterConstantNode -> IntConstant(expr)
  is FloatingConstantNode -> FltConstant(expr)
  is StringLiteralNode -> StrConstant(expr)
}

/**
 * Transforms top-level expressions passed through [sequentialize] to a list of instructions, each
 * of which is placed in a [VirtualRegister].
 */
fun createInstructions(
    exprs: List<Expression>,
    targetData: MachineTargetData,
    registerIds: IdCounter
): List<IRInstruction> {
  val builder = IRBuilderContext(targetData, registerIds)
  for (expr in exprs) {
    val folded = targetData.doConstantFolding(expr)
    val lastValue = builder.buildOperand(folded)
    if (lastValue is ConstantValue) {
      builder.instructions += ConstantRegisterInstr(builder.newRegister(lastValue.type), lastValue)
    } else if (lastValue is Variable) {
      builder.instructions += LoadInstr(builder.newRegister(lastValue.type), lastValue)
    }
  }
  return builder.instructions
}
