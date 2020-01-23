package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

class IRTests {
  private fun createIR(vararg exprs: Expression): List<IRInstruction> {
    val instrs = createInstructions(exprs.toList(), MachineTargetData.x64, IdCounter())
    val registerIds = instrs.filterIsInstance(ResultInstruction::class.java).map { it.result.id }
    // No virtual register is stored to twice
    assert(registerIds.distinct() == registerIds)
    return instrs
  }

  @Test
  fun `IR With Int Constants`() {
    val ir = createIR(1 add (2 mul 3) sub 5)
    for (i in ir) {
      check(i is IntegralInstruction || i is ConstantRegisterInstr)
    }
  }

  @Test
  fun `IR Variable Store`() {
    val ir = createIR(nameRef("a", SignedIntType) assign (1 add (2 mul 3) sub 5))
    val last = ir.last()
    check(last is StoreInstr)
    assertEquals(nameRef("a", SignedIntType), (last.target as Variable).tid)
  }

  @Test
  fun `IR Variable Use`() {
    val ir = createIR(nameRef("a", SignedIntType) add 5)
    val load = ir[0]
    check(load is IntBinary)
    assertEquals(nameRef("a", SignedIntType), (load.lhs as Variable).tid)
  }

  @Test
  fun `IR Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(nameRef("v", arrayType)[2])
    val ptrAdd = ir[0]
    check(ptrAdd is IntBinary)
    assertEquals(IntegralBinaryOps.ADD, ptrAdd.op)
    val offset = ptrAdd.rhs
    check(offset is IntConstant)
    assertEquals(2L, offset.value)
    check(ir[1] is ReinterpretCast)
    val deref = ir[2]
    check(deref is LoadInstr)
    assertEquals(arrayType.elementType, deref.result.type)
  }

  @Test
  fun `IR Store To Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(nameRef("v", arrayType)[2] assign 55)
    val store = ir.last()
    check(store is StoreInstr)
    assertEquals(ptr(SignedIntType), store.target.type)
    val const = store.value
    check(const is IntConstant)
    assertEquals(55L, const.value)
  }

  @Test
  fun `IR Address Of Array Subscript`() {
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(4)))
    val ir = createIR(UnaryOperators.REF[nameRef("v", arrayType)[2]])
    val ptrAdd = ir[0]
    check(ptrAdd is IntBinary)
    assertEquals(IntegralBinaryOps.ADD, ptrAdd.op)
    val offset = ptrAdd.rhs
    check(offset is IntConstant)
    assertEquals(2L, offset.value)
    val cast = ir[1]
    check(cast is ReinterpretCast)
    assertEquals(ptr(SignedIntType), cast.castTo)
  }

  @Test
  fun `IR Member Access With Struct Pointer`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", ptr(structType)) arrow nameRef("y", SignedIntType))
    val ptrAdd = ir[0]
    check(ptrAdd is IntBinary)
    assertEquals(IntegralBinaryOps.ADD, ptrAdd.op)
    val offset = ptrAdd.rhs
    check(offset is IntConstant)
    assertEquals(MachineTargetData.x64.intSizeBytes.toLong(), offset.value)
    check(ir[1] is ReinterpretCast)
    val deref = ir[2]
    check(deref is LoadInstr)
    assertEquals(SignedIntType, deref.result.type)
  }

  @Test
  fun `IR Member Access Struct Directly`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", structType) dot nameRef("y", SignedIntType))
    val ptrAdd = ir[0]
    check(ptrAdd is IntBinary)
    assertEquals(IntegralBinaryOps.ADD, ptrAdd.op)
    val base = ptrAdd.lhs
    check(base is Variable)
    assertEquals(nameRef("u", ptr(structType)), base.tid)
    val offset = ptrAdd.rhs
    check(offset is IntConstant)
    assertEquals(MachineTargetData.x64.intSizeBytes.toLong(), offset.value)
    check(ir[1] is ReinterpretCast)
    val deref = ir[2]
    check(deref is LoadInstr)
    assertEquals(SignedIntType, deref.result.type)
  }

  @Test
  fun `IR Store To Struct`() {
    val structSpec = struct("vec2", int declare "x", int declare "y").toSpec()
    val structType = typeNameOf(structSpec, AbstractDeclarator.blank())
    val member = nameRef("u", structType) dot nameRef("y", SignedIntType)
    val ir = createIR(member assign 42)
    val store = ir[2]
    check(store is StoreInstr)
    assertEquals(ptr(SignedIntType), store.target.type)
    val const = store.value
    check(const is IntConstant)
    assertEquals(42L, const.value)
  }

  @Test
  fun `IR Member Access Union Directly`() {
    val unionSpec = union("name", int declare "x", double declare "y").toSpec()
    val unionType = typeNameOf(unionSpec, AbstractDeclarator.blank())
    val ir = createIR(nameRef("u", unionType) dot nameRef("y", DoubleType))
    val castToTargetType = ir[0]
    check(castToTargetType is ReinterpretCast)
    assertEquals(unionType, castToTargetType.operand.type)
    assertEquals(DoubleType, castToTargetType.castTo)
  }

  @Test
  fun `IR Store To Union`() {
    val unionSpec = union("name", int declare "x", double declare "y").toSpec()
    val unionType = typeNameOf(unionSpec, AbstractDeclarator.blank())
    val member = nameRef("u", unionType) dot nameRef("y", SignedIntType)
    val ir = createIR(member assign 42)
    val store = ir[1]
    check(store is StoreInstr)
    assertEquals(ptr(SignedIntType), store.target.type)
    val const = store.value
    check(const is IntConstant)
    assertEquals(42L, const.value)
  }

  @Test
  fun `IR Multiple Adds`() {
    val x = nameRef("x", SignedIntType)
    val y = nameRef("y", SignedIntType)
    val z = nameRef("z", SignedIntType)
    val ir = createIR(x add y add z add 1)
    for (i in ir) {
      assert(i is IntBinary)
    }
  }
}
