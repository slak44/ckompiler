package slak.test.analysis

import org.junit.After
import org.junit.Before
import org.junit.Test
import slak.ckompiler.DebugHandler
import slak.ckompiler.Diagnostic
import slak.ckompiler.DiagnosticId
import slak.ckompiler.analysis.sequentialize
import slak.ckompiler.parser.SignedIntType
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class SequenceTests {
  private lateinit var debugHandler: DebugHandler

  @Before
  fun prepareHandler() {
    debugHandler = DebugHandler("SequenceTests", "<none>", "")
  }

  @After
  fun printDiags() {
    debugHandler.diags.forEach(Diagnostic::print)
  }

  @Test
  fun `Linear Expression Is Unchanged`() {
    val expr = 1 add 2 mul 6 add sizeOf(1 add 1)
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assert(sequencedBefore.isEmpty())
    assert(sequencedAfter.isEmpty())
    assertEquals(expr, remaining)
  }

  @Test
  fun `Assignment Is Hoisted`() {
    val x = nameRef("x", SignedIntType)
    val assignment = x assign (2 add 3)
    val expr = 5 mul assignment
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(1, sequencedBefore.size)
    assertEquals(assignment, sequencedBefore[0])
    assert(sequencedAfter.isEmpty())
    assertEquals(5 mul x, remaining)
  }

  @Test
  fun `Leftover TypedIdentifier Is Cloned After Assignment Hoisting`() {
    val x = nameRef("x", SignedIntType)
    val assignment = x assign (2 add 3)
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(assignment)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(assignment), sequencedBefore)
    assert(sequencedAfter.isEmpty())
    // Equal, but not the same: a copy must have been made
    assertEquals(x, remaining)
    assertNotSame(x, remaining)
  }

  @Test
  fun `Prefix Increment Is Hoisted`() {
    val x = nameRef("x", SignedIntType)
    val expr = 5 mul prefixInc(x)
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(prefixInc(x)), sequencedBefore)
    assert(sequencedAfter.isEmpty())
    assertEquals(5 mul x, remaining)
  }

  @Test
  fun `Leftover TypedIdentifier Is Cloned After Increment Hoisting`() {
    val x = nameRef("x", SignedIntType)
    val expr = prefixInc(x)
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(expr), sequencedBefore)
    assert(sequencedAfter.isEmpty())
    // Equal, but not the same: a copy must have been made
    assertEquals(x, remaining)
    assertNotSame(x, remaining)
  }

  @Test
  fun `Postfix Increment Is After`() {
    val x = nameRef("x", SignedIntType)
    val expr = 5 mul postfixInc(x)
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assert(sequencedBefore.isEmpty())
    assertEquals(listOf(postfixInc(x)), sequencedAfter)
    assertEquals(5 mul x, remaining)
  }

  @Test
  fun `Multiple Unsequenced Increments`() {
    val x = nameRef("x", SignedIntType)
    val expr = postfixInc(x) mul postfixInc(x)
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(expr)
    debugHandler.assertDiags(DiagnosticId.UNSEQUENCED_MODS)
    assert(sequencedBefore.isEmpty())
    assertEquals(listOf(postfixInc(x), postfixInc(x)), sequencedAfter)
    assertEquals(x mul x, remaining)
  }

  @Test
  fun `Comma LHS Is Entirely Before`() {
    val x = nameRef("x", SignedIntType)
    val expr = postfixInc(x) comma 123
    val (sequencedBefore, remaining, sequencedAfter) = debugHandler.sequentialize(expr)
    debugHandler.assertNoDiagnostics()
    assertEquals(listOf(x, postfixInc(x)), sequencedBefore)
    assert(sequencedAfter.isEmpty())
    assertEquals(int(123), remaining)
  }
}
