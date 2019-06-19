package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.sequentialize
import slak.ckompiler.parser.PostfixIncrement
import slak.ckompiler.parser.PrefixIncrement
import slak.ckompiler.parser.SignedIntType
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class SequenceTests {
  @Test
  fun `Linear Expression Is Unchanged`() {
    val expr = 1 add 2 mul 6 add sizeOf(1 add 1)
    val (sequencedBefore, remaining, sequencedAfter) = sequentialize(expr)
    assert(sequencedBefore.isEmpty())
    assert(sequencedAfter.isEmpty())
    assertEquals(expr, remaining)
  }

  @Test
  fun `Assignment Is Hoisted`() {
    val x = nameRef("x", SignedIntType)
    val assignment = x assign (2 add 3)
    val expr = 5 mul assignment
    val (sequencedBefore, remaining, sequencedAfter) = sequentialize(expr)
    assertEquals(1, sequencedBefore.size)
    assertEquals(assignment, sequencedBefore[0])
    assert(sequencedAfter.isEmpty())
    assertEquals(5 mul x, remaining)
  }

  @Test
  fun `Leftover TypedIdentifier Is Cloned After Assignment Hoisting`() {
    val x = nameRef("x", SignedIntType)
    val assignment = x assign (2 add 3)
    val (sequencedBefore, remaining, sequencedAfter) = sequentialize(assignment)
    assertEquals(listOf(assignment), sequencedBefore)
    assert(sequencedAfter.isEmpty())
    // Equal, but not the same: a copy must have been made
    assertEquals(x, remaining)
    assertNotSame(x, remaining)
  }

  @Test
  fun `Prefix Increment Is Hoisted`() {
    val x = nameRef("x", SignedIntType)
    val expr = 5 mul PrefixIncrement(x)
    val (sequencedBefore, remaining, sequencedAfter) = sequentialize(expr)
    assertEquals(1, sequencedBefore.size)
    assertEquals(PrefixIncrement(x), sequencedBefore[0])
    assert(sequencedAfter.isEmpty())
    assertEquals(5 mul x, remaining)
  }

  @Test
  fun `Leftover TypedIdentifier Is Cloned After Increment Hoisting`() {
    val x = nameRef("x", SignedIntType)
    val expr = PrefixIncrement(x)
    val (sequencedBefore, remaining, sequencedAfter) = sequentialize(expr)
    assertEquals(listOf(expr), sequencedBefore)
    assert(sequencedAfter.isEmpty())
    // Equal, but not the same: a copy must have been made
    assertEquals(x, remaining)
    assertNotSame(x, remaining)
  }

  @Test
  fun `Postfix Increment Is After`() {
    val x = nameRef("x", SignedIntType)
    val expr = 5 mul PostfixIncrement(x)
    val (sequencedBefore, remaining, sequencedAfter) = sequentialize(expr)
    assert(sequencedBefore.isEmpty())
    assertEquals(1, sequencedAfter.size)
    assertEquals(PostfixIncrement(x), sequencedAfter[0])
    assertEquals(5 mul x, remaining)
  }
}
