package slak.test.backend

import org.junit.jupiter.api.Test
import slak.ckompiler.analysis.IntBinary
import slak.ckompiler.analysis.IntConstant
import slak.ckompiler.analysis.IntegralBinaryOps
import slak.ckompiler.analysis.VirtualRegister
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.Imm
import slak.ckompiler.backend.x64.ModRM
import slak.ckompiler.backend.x64.X64InstrTemplate
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.parser.SignedIntType
import slak.test.int
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals

class X64Tests {
  private fun regAlloc(resourceName: String): AllocationResult {
    val cfg = prepareCFG(resource(resourceName), source)
    val res = X64Target.regAlloc(cfg, X64Target.instructionSelection(cfg))
    val (newLists, allocation, _) = res
    for ((block, list) in newLists) {
      println(block)
      println(list.stringify())
      println()
    }
    for ((value, register) in allocation) {
      println("allocate $value to $register")
    }
    val final = X64Target.applyAllocation(cfg, res)
    println(final.joinToString("\n", prefix = "\n"))
    return res
  }

  @Test
  fun `Instruction Selection On Simple Adds`() {
    val add = IntBinary(
        VirtualRegister(1, SignedIntType),
        IntegralBinaryOps.ADD,
        VirtualRegister(1, SignedIntType),
        IntConstant(int(4))
    )
    val mi = X64Target.expandMacroFor(add).single()
    assert(mi.template is X64InstrTemplate)
    assert(mi.template in slak.ckompiler.backend.x64.add)
    val instr = mi.template as X64InstrTemplate
    assertEquals(2, instr.operands.size)
    assert(instr.operands[0] is ModRM)
    assert(instr.operands[1] is Imm)
  }

  @Test
  fun `Instruction Selection On CFG`() {
    val cfg = prepareCFG(resource("codegen/addsAndMovs.c"), source)
    val iLists = X64Target.instructionSelection(cfg)
    for (list in iLists) {
      println(list.value.stringify())
    }
  }

  @Test
  fun `Register Allocation`() {
    val (_, allocs) = regAlloc("codegen/interference.c")
    assertEquals(3, allocs.values.distinct().size)
  }

  @Test
  fun `Register Allocation With Register Pressure`() {
    val (_, allocs, stackSlots) = regAlloc("codegen/highRegisterPressure.c")
    assertEquals(14, allocs.values.filter { it.valueClass != Memory }.distinct().size)
    val spilled = allocs.entries.filter { it.value.valueClass == Memory }
    assertEquals(1, spilled.size)
    assertEquals(1, stackSlots.size)
  }

  @Test
  fun `Register Allocation Many Non-Interfering`() {
    val (_, allocs, stackSlots) = regAlloc("codegen/parallelLiveRanges.c")
    assertEquals(0, allocs.values.filter { it.valueClass == Memory }.size)
    assert(stackSlots.isEmpty())
  }
}
