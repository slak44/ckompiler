package slak.ckompiler.parser

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.analysis.evalBinary
import slak.ckompiler.analysis.evalCast
import slak.ckompiler.analysis.evalUnary

object ConstExprIdents : IdentSearchable, TypeNameParser {
  override fun parseTypeName(endIdx: Int): TypeName? {
    return null
  }

  override fun searchIdent(target: String): OrdinaryIdentifier {
    return TypedIdentifier(target, SignedIntType)
  }
}

enum class ConstantExprType {
  PREPROCESSOR, DECLARATOR_ARRAY_SIZE, INTEGER_CONSTANT
}

interface IConstantExprParser : IDebugHandler {
  /**
   * Calls [IExpressionParser.parseExpr], then parses the constant expression.
   * Generates diagnostics and calculates the final value of the constant.
   */
  fun parseConstant(endIdx: Int): ExprConstantNode?

  /**
   * Go through a parsed expression and forbid certain constructs, while evaluating the constant
   * expression. Diagnostics encountered are returned, and are not sent to the enclosing
   * [IDebugHandler].
   */
  fun evaluateExpr(expr: Expression): Pair<ExprConstantNode, List<Diagnostic>>
}

/** C standard: 6.6 */
class ConstantExprParser(
    val type: ConstantExprType,
    exprParser: ExpressionParser,
) : IConstantExprParser,
    IDebugHandler by exprParser,
    ITokenHandler by exprParser,
    IExpressionParser by exprParser {

  private val targetData: MachineTargetData = exprParser.machineTargetData

  constructor(
      type: ConstantExprType,
      parenMatcher: ParenMatcher,
      identSearchable: IdentSearchable,
      typeNameParser: TypeNameParser,
      machineTargetData: MachineTargetData,
  ) : this(type, ExpressionParser(parenMatcher, identSearchable, typeNameParser, machineTargetData))

  override fun parseConstant(endIdx: Int): ExprConstantNode? {
    if (endIdx == 0) return null
    val expr = parseExpr(endIdx) ?: return null
    val (const, diags) = evaluateExpr(expr)
    includeNestedDiags(diags)
    return const
  }

  override fun evaluateExpr(expr: Expression): Pair<ExprConstantNode, List<Diagnostic>> {
    val maybeSuppressed = mutableListOf<Diagnostic>()
    return evaluateExprImpl(maybeSuppressed, expr) to maybeSuppressed
  }

  private fun evaluateExprImpl(
      diags: MutableList<Diagnostic>,
      expr: Expression,
  ): ExprConstantNode = when (expr) {
    is TernaryConditional, is MemberAccessExpression -> TODO("deal with this")
    is ErrorExpression, is VoidExpression, is IntegerConstantNode, is CharacterConstantNode -> expr as ExprConstantNode
    is FunctionCall, is ArraySubscript, is IncDecOperation -> {
      diags += createDiagnostic {
        id = DiagnosticId.EXPR_NOT_CONSTANT
        errorOn(expr)
      }
      ErrorExpression().withRange(expr)
    }
    is TypedIdentifier -> when (type) {
      ConstantExprType.PREPROCESSOR -> {
        logger.throwICE("Impossible to get here; identifiers evaluate to 0 in PP")
      }
      ConstantExprType.INTEGER_CONSTANT,
      ConstantExprType.DECLARATOR_ARRAY_SIZE,
      -> ErrorExpression().withRange(expr)
    }
    is StringLiteralNode, is FloatingConstantNode -> when (type) {
      ConstantExprType.PREPROCESSOR -> {
        diags += createDiagnostic {
          id = DiagnosticId.INVALID_LITERAL_IN_PP
          formatArgs(if (expr is StringLiteralNode) "string" else "floating")
          errorOn(expr)
        }
        ErrorExpression().withRange(expr)
      }
      // Floats are allowed here in certain places
      // This will check the type of the result anyway
      ConstantExprType.INTEGER_CONSTANT,
      ConstantExprType.DECLARATOR_ARRAY_SIZE,
      -> expr as ExprConstantNode
    }
    is CastExpression -> when (type) {
      ConstantExprType.PREPROCESSOR -> {
        logger.throwICE("Impossible to get here; type names cannot be" +
            " recognized in preprocessor, so casts can't either")
      }
      ConstantExprType.INTEGER_CONSTANT,
      ConstantExprType.DECLARATOR_ARRAY_SIZE,
      -> {
        evalCast(expr.type, evaluateExprImpl(diags, expr.target))
      }
    }
    is UnaryExpression -> {
      val operand = evaluateExprImpl(diags, expr.operand)
      when {
        expr.op == UnaryOperators.REF || expr.op == UnaryOperators.DEREF -> {
          diags += createDiagnostic {
            id = DiagnosticId.EXPR_NOT_CONSTANT
            errorOn(expr)
          }
          ErrorExpression().withRange(expr)
        }
        operand.type is ErrorType -> ErrorExpression().withRange(expr)
        else -> evalUnary(operand, expr.op)
      }
    }
    is BinaryExpression -> {
      val lhs = evaluateExprImpl(diags, expr.lhs)
      val rhs = evaluateExprImpl(diags, expr.rhs)
      when {
        expr.op in assignmentOps || expr.op == BinaryOperators.COMMA -> {
          diags += createDiagnostic {
            id = DiagnosticId.EXPR_NOT_CONSTANT
            errorOn(expr)
          }
          ErrorExpression().withRange(expr)
        }
        expr.type is ErrorType || lhs.type is ErrorType || rhs.type is ErrorType -> {
          ErrorExpression().withRange(expr)
        }
        else -> evalBinary(lhs, rhs, expr.op, expr.type)
      }
    }
    is SizeofTypeName -> {
      IntegerConstantNode(targetData.sizeOf(expr.sizeOfWho).toLong()).withRange(expr)
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
