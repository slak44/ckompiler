package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.IdCounter
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

class IRTests {
  private fun createIR(vararg exprs: Expression): List<IRInstruction> {
    val (instrs, _) = createInstructions(exprs.toList(), MachineTargetData.x64, IdCounter())
    val registerIds = instrs
        .filter { it !is StoreMemory }
        .mapNotNull { (it.result as? VirtualRegister)?.registerId }
    // No virtual register is stored to twice
    assert(registerIds.distinct() == registerIds)
    return instrs
  }

  private fun assertIsPtrAdd(i: IRInstruction, ptrTo: TypeName, offset: Int) {
    val ptrAdd = i as IntBinary
    assertEquals(IntegralBinaryOps.ADD, ptrAdd.op)
    assertEquals(ptr(ptrTo), ptrAdd.result.type)
    val offsetActual = (ptrAdd.rhs as? IntConstant)?.value
    assertEquals(offset.toLong(), offsetActual)
  }

  @Test
  fun `IR With Int Constants`() {
    val ir = createIR(1 add (2 mul 3) sub 5)
    for (i in ir) {
      check(i is IntegralInstruction || i is MoveInstr)
    }
  }

  @Test
  fun `IR Variable Store`() {
    val ir = createIR(intVar("a") assign (1 add (2 mul 3) sub 5))
    val last = ir.last()
    check(last is MoveInstr)
    assertEquals(intVar("a"), (last.result as Variable).tid)
  }

  @Test
  fun `IR Variable Use`() {
    val ir = createIR(intVar("a") add 5)
    val load = ir[0]
    check(load is IntBinary)
    assertEquals(intVar("a"), (load.lhs as Variable).tid)
  }

  @Test
  fun `IR Pointer Dereference`() {
    val ir = createIR(
        nameRef("a", ptr(SignedIntType)) assign 12345,
        UnaryOperators.DEREF[nameRef("a", ptr(SignedIntType))] assign 3
    )
    val store = ir[2] as StoreMemory
    val storeTarget = store.storeTo as Variable
    assertEquals("a", storeTarget.name)
    val constant = (store.value as? IntConstant)?.value
    assertEquals(3, constant)
  }

  @Test
  fun `IR Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(nameRef("v", arrayType)[2])
    assertIsPtrAdd(ir[0], SignedIntType, 2)
    val load = ir[1] as LoadMemory
    assertEquals(SignedIntType, load.result.type)
  }

  @Test
  fun `IR Store To Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(nameRef("v", arrayType)[2] assign 55)
    assertIsPtrAdd(ir[0], SignedIntType, 2)
    val store = ir.last() as StoreMemory
    val const = requireNotNull(store.value as? IntConstant).value
    assertEquals(55L, const)
  }

  @Test
  fun `IR Address Of Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(UnaryOperators.REF[nameRef("v", arrayType)[2]])
    assertIsPtrAdd(ir[0], SignedIntType, 2)
  }

  @Test
  fun `IR Member Access With Struct Pointer`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", ptr(structType)) arrow intVar("y"))
    assertIsPtrAdd(ir[0], structType, MachineTargetData.x64.intSizeBytes)
    val load = ir.last() as LoadMemory
    assertEquals(SignedIntType, load.result.type)
  }

  @Test
  fun `IR Member Access Struct Directly`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", structType) dot intVar("y"))
    val ptrAdd = ir[0] as IntBinary
    assert(ptrAdd.lhs is StackVariable)
    assertIsPtrAdd(ptrAdd, structType, MachineTargetData.x64.intSizeBytes)
    val load = ir.last() as LoadMemory
    assertEquals(SignedIntType, load.result.type)
  }

  @Test
  fun `IR Store To Struct`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val member = nameRef("u", structType) dot intVar("y")
    val ir = createIR(member assign 42)
    val ptrAdd = ir[0] as IntBinary
    assert(ptrAdd.lhs is StackVariable)
    assertIsPtrAdd(ptrAdd, structType, MachineTargetData.x64.intSizeBytes)
    val store = ir.last() as StoreMemory
    val const = requireNotNull(store.value as? IntConstant)
    assertEquals(42L, const.value)
  }

  @Test
  fun `IR Member Access Union Directly`() {
    val unionSpec = union("name", int declare "x", double declare "y").toSpec()
    val unionType = typeNameOf(unionSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", unionType) dot nameRef("y", DoubleType))
    val castToTargetType = ir[0] as ReinterpretCast
    assert(castToTargetType.operand is StackVariable)
    assertEquals(ptr(unionType), castToTargetType.operand.type)
    assertEquals(ptr(DoubleType), castToTargetType.result.type)
  }

  @Test
  fun `IR Store To Union`() {
    val unionSpec = union("name", int declare "x", double declare "y").toSpec()
    val unionType = typeNameOf(unionSpec, AbstractDeclarator.blank())
    val member = nameRef("u", unionType) dot intVar("y")
    val ir = createIR(member assign 42)
    assert(ir[0] is ReinterpretCast)
    val store = ir.last() as StoreMemory
    val const = requireNotNull(store.value as? IntConstant).value
    assertEquals(42L, const)
  }

  @Test
  fun `IR Multiple Adds`() {
    val x = intVar("x")
    val y = intVar("y")
    val z = intVar("z")
    val ir = createIR(x add y add z add 1)
    for (i in ir) {
      assert(i is IntBinary)
    }
  }

  @Test
  fun `IR Useless Cast Is Not Generated`() {
    val ir = createIR(SignedIntType.cast(1 add 3))
    assert(ir.none { it is StructuralCast || it is ReinterpretCast })
  }

  @Test
  fun `IR Useless Cast Is Not Generated For Cast Between Const`() {
    val ir = createIR((const + SignedIntType).cast(nameRef("foo", const + SignedIntType)))
    assert(ir.none { it is StructuralCast || it is ReinterpretCast })
  }

  @Test
  fun `IR Useless Cast Is Not Generated For Const Int To Int Cast`() {
    val ir = createIR(SignedIntType.cast(nameRef("foo", const + SignedIntType)))
    assert(ir.none { it is StructuralCast || it is ReinterpretCast })
  }
}
