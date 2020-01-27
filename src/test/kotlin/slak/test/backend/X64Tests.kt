package slak.test.backend

import org.junit.jupiter.api.Test
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.X64Target
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
  fun `Register Allocation With Varying Sizes`() {
    val (_, allocs) = regAlloc("codegen/storeCmp.c")
    TODO()
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
