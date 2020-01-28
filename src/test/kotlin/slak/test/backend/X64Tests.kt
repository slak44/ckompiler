package slak.test.backend

import org.junit.jupiter.api.Test
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.VirtualRegister
import slak.ckompiler.backend.AllocationResult
import slak.ckompiler.backend.Memory
import slak.ckompiler.backend.regAlloc
import slak.ckompiler.backend.stringify
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.backend.x64.setcc
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals

class X64Tests {
  private fun regAlloc(resourceName: String): AllocationResult {
    val cfg = prepareCFG(resource(resourceName), source)
    val gen = X64Generator(cfg)
    val res = X64Target.regAlloc(cfg, gen.instructionSelection())
    val (newLists, allocation, _) = res
    for ((block, list) in newLists) {
      println(block)
      println(list.stringify())
      println()
    }
    for ((value, register) in allocation) {
      println("allocate $value to $register")
    }
    val final = gen.applyAllocation(res)
    for ((block, list) in final) {
      println(block)
      println(list.joinToString("\n", prefix = "\n"))
      println()
    }
    return res
  }

  @Test
  fun `Instruction Selection On CFG`() {
    val cfg = prepareCFG(resource("codegen/addsAndMovs.c"), source)
    val iLists = X64Generator(cfg).instructionSelection()
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
  fun `Register Allocation With Varying Sizes (SETcc Instruction)`() {
    val (iLists, _) = regAlloc("codegen/storeCmp.c")
    val (_, mi) = iLists.entries.first { it.key.isRoot }
    val setInstr = mi.first { it.template in setcc.getOrElse(it.template.name, ::emptyList) }
    assertEquals(1, setInstr.operands.size)
    assert(setInstr.operands[0] is VirtualRegister)
    assertEquals(1, MachineTargetData.x64.sizeOf(setInstr.operands[0].type))
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
