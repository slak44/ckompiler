package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IRTests {
  private fun createIR(vararg exprs: Expression): List<IRInstruction> {
    val instrs = createInstructions(exprs.toList(), MachineTargetData.x64, IdCounter(), IdCounter())
    val registerIds = instrs
        .filter { it !is StoreMemory }
        .mapNotNull { (it.result as? VirtualRegister)?.id }
    // No virtual register is stored to twice
    assert(registerIds.distinct() == registerIds)
    return instrs
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
  fun `IR Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(nameRef("v", arrayType)[2])
    val load = ir[0]
    check(load is LoadMemory)
    assertEquals(SignedIntType, load.result.type)
    val v = load.ptr.ptr
    check(v is Variable)
    assertEquals("v", v.name)
    val offset = requireNotNull(load.ptr.offset as? IntConstant)
    assertEquals(2, offset.value)
  }

  @Test
  fun `IR Store To Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(nameRef("v", arrayType)[2] assign 55)
    val store = requireNotNull(ir.last() as? StoreMemory)
    val v = requireNotNull(store.ptr.ptr as? Variable)
    assertEquals("v", v.name)
    val offset = requireNotNull(store.ptr.offset as? IntConstant)
    assertEquals(2, offset.value)
    val const = requireNotNull(store.value as? IntConstant)
    assertEquals(55L, const.value)
  }

  @Test
  fun `IR Address Of Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(UnaryOperators.REF[nameRef("v", arrayType)[2]])
    val load = ir[0]
    check(load is MoveInstr)
    assertEquals(ptr(SignedIntType), load.result.type)
    val memRef = requireNotNull(load.value as? MemoryReference)
    val v = memRef.ptr
    check(v is Variable)
    assertEquals("v", v.name)
    val offset = requireNotNull(memRef.offset as? IntConstant)
    assertEquals(2, offset.value)
  }

  @Test
  fun `IR Member Access With Struct Pointer`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", ptr(structType)) arrow intVar("y"))
    val load = requireNotNull(ir.last() as? LoadMemory)
    assertEquals(SignedIntType, load.result.type)
    val ptr = requireNotNull(load.ptr.ptr.type.unqualify() as? PointerType)
    assertEquals(structType, ptr.referencedType)
    val offset = requireNotNull(load.ptr.offset as? IntConstant)
    assertEquals(MachineTargetData.x64.intSizeBytes.toLong(), offset.value)
  }

  @Test
  fun `IR Member Access Struct Directly`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", structType) dot intVar("y"))
    val load = requireNotNull(ir.last() as? LoadMemory)
    assertEquals(SignedIntType, load.result.type)
    val structPtr = requireNotNull(load.ptr.ptr as? MemoryReference)
    val ptr = requireNotNull(structPtr.type.unqualify() as? PointerType)
    assertEquals(structType, ptr.referencedType)
    val offset = requireNotNull(load.ptr.offset as? IntConstant)
    assertEquals(MachineTargetData.x64.intSizeBytes.toLong(), offset.value)
  }

  @Test
  fun `IR Store To Struct`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val member = nameRef("u", structType) dot intVar("y")
    val ir = createIR(member assign 42)
    val store = requireNotNull(ir.last() as? StoreMemory)
    val structPtr = requireNotNull(store.ptr.ptr as? MemoryReference)
    val ptr = requireNotNull(structPtr.type.unqualify() as? PointerType)
    assertEquals(structType, ptr.referencedType)
    val offset = requireNotNull(store.ptr.offset as? IntConstant)
    assertEquals(MachineTargetData.x64.intSizeBytes.toLong(), offset.value)
    val const = requireNotNull(store.value as? IntConstant)
    assertEquals(42L, const.value)
  }

  @Test
  fun `IR Member Access Union Directly`() {
    val unionSpec = union("name", int declare "x", double declare "y").toSpec()
    val unionType = typeNameOf(unionSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", unionType) dot nameRef("y", DoubleType))
    val castToTargetType = requireNotNull(ir[0] as? ReinterpretCast)
    assertEquals(unionType, castToTargetType.operand.type)
    assertEquals(DoubleType, castToTargetType.result.type)
  }

  @Test
  fun `IR Store To Union`() {
    val unionSpec = union("name", int declare "x", double declare "y").toSpec()
    val unionType = typeNameOf(unionSpec, AbstractDeclarator.blank())
    val member = nameRef("u", unionType) dot intVar("y")
    val ir = createIR(member assign 42)
    assert(ir[0] is ReinterpretCast)
    val store = requireNotNull(ir.last() as? StoreMemory)
    assertNull(store.ptr.offset)
    val target = requireNotNull(store.ptr.ptr as? VirtualRegister)
    assertEquals(ptr(SignedIntType), target.type)
    val const = requireNotNull(store.value as? IntConstant)
    assertEquals(42L, const.value)
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
}
