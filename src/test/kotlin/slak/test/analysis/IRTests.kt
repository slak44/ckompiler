package slak.test.analysis

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import slak.ckompiler.DebugHandler
import slak.ckompiler.Diagnostic
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.DoubleType
import slak.ckompiler.parser.Expression
import slak.ckompiler.parser.FloatType
import slak.ckompiler.parser.SignedIntType
import slak.test.*
import kotlin.test.assertEquals

class IRTests {
  private lateinit var debugHandler: DebugHandler

  @BeforeEach
  fun prepareHandler() {
    debugHandler = DebugHandler("IRTests", "<none>", "")
  }

  @AfterEach
  fun printDiags() {
    debugHandler.diags.forEach(Diagnostic::print)
    println()
  }

  private fun List<Expression>.toIRList(): List<IRExpression> {
    val context = IRLoweringContext(MachineTargetData.x64, enableFolding = false)
    forEach(context::buildIR)
    return context.ir
  }

  private fun List<IRExpression>.print() = println(joinToString("\n"))

  private fun List<IRExpression>.assertSSAForTemporaries() {
    val syntheticStores = filter { it is Store && it.isSynthetic }
    val temporaries = syntheticStores.map { (it as Store).target }
    // All synthetic assignment targets should be different variables
    assertEquals(temporaries, temporaries.distinct())
  }

  @Test
  fun `Basic Expression`() {
    val expr = 1 add (23 mul 70)
    val ir = listOf(expr).toIRList()
    ir.print()
    ir.assertSSAForTemporaries()
    // This is separated into 2 operations, and stored in 2 temporaries
    assertEquals(2, ir.size)
    assert(ir[0] is Store)
    assert(ir[1] is Store)
  }

  @Test
  fun `Simple Assignment`() {
    val x = nameRef("x", SignedIntType)
    val expr = x assign 1
    val ir = listOf(expr).toIRList()
    ir.print()
    ir.assertSSAForTemporaries()
    // This should not do much
    assertEquals(listOf(Store(
        ComputeReference(x, isSynthetic = false),
        ComputeInteger(int(1)),
        isSynthetic = false
    )), ir)
  }

  @Test
  fun `Simple Compound Assignment`() {
    val x = nameRef("x", SignedIntType)
    val expr = x plusAssign 1
    val ir = listOf(expr).toIRList()
    ir.print()
    ir.assertSSAForTemporaries()
    // FIXME: incomplete
  }

  @Test
  fun `Implicit Casts In Compound Assignments Are Correct`() {
    val x = nameRef("x", FloatType)
    val y = nameRef("y", DoubleType)
    val ir = listOf(
        x assign float(1.0),
        y assign 2.0,
        x plusAssign (y sub 0.5)
    ).toIRList()
    ir.print()
    ir.assertSSAForTemporaries()
    // FIXME: this should not be the IR's problem
  }

  @Test
  fun `Sequentialized Expression`() {
    val x = nameRef("x", SignedIntType)
    val y = nameRef("y", SignedIntType)
    val exprs = listOf(
        x plusAssign (123 add y),
        prefixInc(y)
    )
    val seq = exprs.map(debugHandler::sequentialize).flatMap(SequentialExpression::toList)
    val ir = seq.toIRList()
    ir.print()
    ir.assertSSAForTemporaries()
    // FIXME: incomplete
  }
}
