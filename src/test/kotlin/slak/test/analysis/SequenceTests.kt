package slak.test.analysis

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import slak.ckompiler.DebugHandler
import slak.ckompiler.Diagnostic
import slak.ckompiler.DiagnosticId
import slak.ckompiler.analysis.sequentialize
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class SequenceTests {
  private lateinit var debugHandler: DebugHandler

  @BeforeEach
  fun prepareHandler() {
    debugHandler = DebugHandler("SequenceTests", "<none>", "")
  }

  @AfterEach
  fun printDiags() {
    debugHandler.diags.forEach(Diagnostic::print)
  }

  private fun Expression.assertIsSynthetic(): TypedIdentifier {
    require(this is TypedIdentifier) { "Expression isn't TypedIdentifier" }
    require(name.startsWith("__")) { "Expression isn't synthetic" }
    return this
  }

  @Test
  fun `Linear Expression Is Unchanged`() {
    val expr = 1 add 2 mul 6 add sizeOf(1 add 1)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assert(sequencedBefore.isEmpty())
    assertEquals(expr, remaining)
  }

  @Test
  fun `Assignment Is Hoisted`() {
    val x = intVar("x")
    val assignment = x assign (2 add 3)
    val expr = 5 mul assignment
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(1, sequencedBefore.size)
    assertEquals(assignment, sequencedBefore[0])
    assertEquals(5 mul x, remaining)
  }

  @ParameterizedTest
  @EnumSource(value = BinaryOperators::class, mode = EnumSource.Mode.INCLUDE, names = [
    "MUL_ASSIGN", "DIV_ASSIGN", "MOD_ASSIGN", "PLUS_ASSIGN", "SUB_ASSIGN",
    "LSH_ASSIGN", "RSH_ASSIGN", "AND_ASSIGN", "XOR_ASSIGN", "OR_ASSIGN"
  ])
  fun `Compound Assignments Are Deconstructed`(compoundAssignOp: BinaryOperators) {
    val x = intVar("x")
    val compoundAssignment = x to (2 add 3) with compoundAssignOp
    val expr = 5 mul compoundAssignment
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(5 mul x, remaining)
    assertEquals(1, sequencedBefore.size)
    // Check that x += 2 + 3 becomes x = x + (2 + 3), basically
    val complementary = compoundAssignOps.getValue(compoundAssignOp)
    assertEquals(x assign (x to (2 add 3) with complementary), sequencedBefore[0])
  }

  @Test
  fun `Implicit Casts In Compound Assignments Are Correct`() {
    val x = nameRef("x", FloatType)
    val y = nameRef("y", DoubleType)
    val expr = x plusAssign (y sub 0.5)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(x, remaining)
    assertEquals(listOf(
        x assign FloatType.cast(DoubleType.cast(x) add (y sub 0.5))
    ), sequencedBefore)
  }

  @Test
  fun `Leftover TypedIdentifier Is Cloned After Assignment Hoisting`() {
    val x = intVar("x")
    val assignment = x assign (2 add 3)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(assignment)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(assignment), sequencedBefore)
    // Equal, but not the same: a copy must have been made
    assertEquals(x, remaining)
    assertNotSame(x, remaining)
  }

  @Test
  fun `Prefix Increment Is Hoisted`() {
    val x = intVar("x")
    val expr = 5 mul prefixInc(x)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(x assign (x add 1)), sequencedBefore)
    assertEquals(5 mul x, remaining)
  }

  @Test
  fun `Leftover TypedIdentifier Is Cloned After Increment Hoisting`() {
    val x = intVar("x")
    val expr = prefixInc(x)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(x assign (x add 1)), sequencedBefore)
    // Equal, but not the same: a copy must have been made
    assertEquals(x, remaining)
    assertNotSame(x, remaining)
  }

  @Test
  fun `Postfix Increment Is After`() {
    val x = intVar("x")
    val expr = 5 mul postfixInc(x)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(x assign (x add 1), sequencedBefore.last())
    check(remaining is BinaryExpression && remaining.op == BinaryOperators.MUL)
    remaining.rhs.assertIsSynthetic()
  }

  @Test
  fun `Postfix Increment In Relational`() {
    val x = intVar("x")
    val expr = 5 equals postfixInc(x)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(x assign (x add 1), sequencedBefore.last())
    check(remaining is BinaryExpression && remaining.op == BinaryOperators.EQ)
    remaining.rhs.assertIsSynthetic()
  }

  @Test
  fun `Multiple Unsequenced Increments`() {
    val x = intVar("x")
    val expr = postfixInc(x) mul postfixInc(x)
    val (_, _) = debugHandler.sequentialize(expr)
    debugHandler.assertDiags(DiagnosticId.UNSEQUENCED_MODS)
  }

  @Test
  fun `Comma LHS Is Entirely Before`() {
    val x = intVar("x")
    val expr = postfixInc(x) comma 123
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assert(sequencedBefore.isNotEmpty())
    assertEquals(int(123), remaining)
  }

  @Test
  fun `Ternary Operator`() {
    val x = intVar("x")
    val y = intVar("y")
    val expr = (x sub 3).qmark(y add 2, y add 3)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    remaining.assertIsSynthetic()
    assertEquals(listOf(remaining assign expr), sequencedBefore)
  }

  @Test
  fun `Logical AND Is Turned Into Ternary`() {
    val x = intVar("x")
    val y = intVar("y")
    val expr = (x equals 1) land (y equals 1)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    remaining.assertIsSynthetic()
    assertEquals(listOf(remaining assign (x equals 1).qmark(y equals 1, 0)), sequencedBefore)
  }

  @Test
  fun `Logical OR Is Turned Into Ternary`() {
    val x = intVar("x")
    val y = intVar("y")
    val expr = (x equals 1) lor (y equals 1)
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    remaining.assertIsSynthetic()
    assertEquals(listOf(remaining assign (x equals 1).qmark(1, y equals 1)), sequencedBefore)
  }

  @Test
  fun `Basic Member Access Is Unchanged`() {
    val vec2 = struct("vec2", int declare "x", int declare "y").toSpec()
    val vec2Type = typeNameOf(vec2, AbstractDeclarator.blank())
    val expr = nameRef("v", vec2Type) dot intVar("x")
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assert(sequencedBefore.isEmpty())
    assertEquals(expr, remaining)
  }

  @Test
  fun `Extract Assignment From Member Access`() {
    val vec2 = struct("vec2", int declare "x", int declare "y").toSpec()
    val vec2Type = typeNameOf(vec2, AbstractDeclarator.blank())
    val assignment = nameRef("v", vec2Type) assign nameRef("u", vec2Type)
    val expr = assignment dot intVar("x")
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(assignment), sequencedBefore)
    assertEquals(nameRef("v", vec2Type) dot intVar("x"), remaining)
  }

  @Test
  fun `Prefix Increment Is Hoisted From Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(20)))
    val v = nameRef("v", arrayType)
    val x = intVar("x")
    val expr = v[prefixInc(x)]
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(v[x], remaining)
    assertEquals(listOf(x assign (x add 1)), sequencedBefore)
  }

  @Test
  fun `Increment Is Hoisted From Array Subscript In Assignment`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(20)))
    val v = nameRef("v", arrayType)
    val x = intVar("x")
    val result = intVar("result")
    val expr = result.assign(v[prefixInc(x)])
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(result, remaining)
    assertEquals(listOf(
        x assign (x add 1),
        result assign v[x]
    ), sequencedBefore)
  }

  @Test
  fun `Prefix Increment Is Hoisted From Cast Expression`() {
    val x = intVar("x")
    val expr = SignedLongLongType.cast(prefixInc(x))
    val (sequencedBefore, remaining) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(SignedLongLongType.cast(x), remaining)
    assertEquals(listOf(x assign (x add 1)), sequencedBefore)
  }
}
