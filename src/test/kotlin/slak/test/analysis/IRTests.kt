package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.Expression
import slak.ckompiler.parser.SignedIntType
import slak.test.*
import kotlin.test.assertEquals

class IRTests {
  private fun createIR(vararg exprs: Expression) =
      createInstructions(exprs.toList(), MachineTargetData.x64, IdCounter())

  @Test
  fun `IR With Int Constants`() {
    val ir = createIR(1 add (2 mul 3) sub 5)
    val registerIds = mutableListOf<Int>()
    for (i in ir) {
      require(i is IntegralInstruction)
      registerIds += i.result.id
    }
    // No virtual register is stored to twice
    assert(registerIds.distinct() == registerIds)
  }

  @Test
  fun `IR Variable Store`() {
    val ir = createIR(nameRef("a", SignedIntType) assign (1 add (2 mul 3) sub 5))
    val registerIds = mutableListOf<Int>()
    for (i in ir.dropLast(1)) {
      require(i is ResultInstruction)
      registerIds += i.result.id
    }
    // No virtual register is stored to twice
    assert(registerIds.distinct() == registerIds)
    val last = ir.last()
    require(last is VarStoreInstr)
    assertEquals("a", last.target.tid.name)
    assertEquals(SignedIntType, last.target.tid.type)
  }

  @Test
  fun `IR Variable Load`() {
    val ir = createIR(nameRef("a", SignedIntType) add 5)
    val load = ir[0]
    require(load is LoadInstr)
    assertEquals("a", load.target.tid.name)
    assertEquals(SignedIntType, load.target.tid.type)
  }
}
