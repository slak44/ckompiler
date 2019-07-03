package slak.test.analysis

import org.junit.After
import org.junit.Before
import org.junit.Test
import slak.ckompiler.DebugHandler
import slak.ckompiler.Diagnostic
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.SignedIntType
import slak.test.*
import kotlin.test.assertEquals

class IRTests {
  private lateinit var debugHandler: DebugHandler

  @Before
  fun prepareHandler() {
    debugHandler = DebugHandler("IRTests", "<none>", "")
  }

  @After
  fun printDiags() {
    debugHandler.diags.forEach(Diagnostic::print)
    println()
  }

  private fun List<IRExpression>.print() = println(joinToString("\n"))

  private fun List<IRExpression>.assertSSAForTemporaries() {
    val syntheticStores = filter { it is Store && it.isSynthetic }
    val temporaries = syntheticStores.map { (it as Store).target.id.toUniqueId() }
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
    assertEquals(
        listOf(Store(ComputeReference(x), ComputeInteger(int(1)), isSynthetic = false)), ir)
  }

  @Test
  fun `Simple Compound Assignment`() {
    val x = nameRef("x", SignedIntType)
    val expr = x plusAssign 1
    val ir = listOf(expr).toIRList()
    ir.print()
    ir.assertSSAForTemporaries()
    // FIXME: incomplete
    for (it in ir) assert(it is Store)
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
    for (it in ir) assert(it is Store)
  }
}
