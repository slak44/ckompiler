package slak.test.analysis

import org.junit.Test
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.doConstantFolding
import slak.ckompiler.parser.*
import slak.test.*
import slak.test.div
import kotlin.test.assertEquals

class ConstantFoldingTests {
  private val td = MachineTargetData.x64

  @Test
  fun `Fold Singular Binary Expression On Ints`() {
    assertEquals(int(2), td.doConstantFolding(1 add 1))
  }

  @Test
  fun `Fold Singular Binary Expression On Floats`() {
    assertEquals(double(2.0), td.doConstantFolding(1.0 add 1.0))
  }

  @Test
  fun `Fold Nested Binary Expressions On Ints`() {
    assertEquals(int(2), td.doConstantFolding(1 add (3 div 3)))
  }

  @Test
  fun `Fold Deeply Nested Binary Expressions On Ints`() {
    assertEquals(int(3), td.doConstantFolding(1 add ((2 mul 3) div 3)))
  }

  @Test
  fun `Fold Sizeof On Type Name`() {
    assertEquals(int(td.sizeOf(SignedLongType).toLong()),
        td.doConstantFolding(sizeOf(SignedLongType)))
  }

  @Test
  fun `Fold Sizeof On Expression`() {
    assertEquals(int(td.sizeOf(SignedIntType).toLong()), td.doConstantFolding(sizeOf(1 add 1)))
  }

  @Test
  fun `Fold Cast To Int`() {
    assertEquals(int(2), td.doConstantFolding(SignedIntType.cast(2.9)))
  }

  @Test
  fun `Fold Cast To Float`() {
    assertEquals(double(2.0), td.doConstantFolding(DoubleType.cast(2)))
  }

  @Test
  fun `Fold Unary Plus`() {
    assertEquals(int(2), td.doConstantFolding(UnaryOperators.PLUS[2]))
  }

  @Test
  fun `Fold Unary Minus`() {
    assertEquals(int(-2), td.doConstantFolding(UnaryOperators.MINUS[2]))
  }

  @Test
  fun `Fold Conditionals`() {
    assertEquals(2.qmark(-4, 25), td.doConstantFolding((1 add 1).qmark(2 sub 6, 5 mul 5)))
  }

  @Test
  fun `Fold In Subscripts`() {
    val array = nameRef("array", ArrayType(SignedIntType, ConstantSize(int(4))))
    assertEquals(array[2], td.doConstantFolding(array[1 add 1]))
  }

  @Test
  fun `Fold Function Arguments`() {
    val f = nameRef("f", FunctionType(VoidType, listOf(SignedIntType, SignedIntType)))
    assertEquals(f(1, 2), td.doConstantFolding(f(2 div 2, SignedIntType.cast(2.9))))
  }

  @Test
  fun `Unfoldable Binary Expression`() {
    val unfoldable = 2 add intVar("a")
    assertEquals(unfoldable, td.doConstantFolding(unfoldable))
  }
}
