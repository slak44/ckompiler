package slak.test.backend

import org.junit.jupiter.api.Test
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.ConstantValue
import slak.ckompiler.analysis.IRValue
import slak.ckompiler.analysis.MemoryReference
import slak.ckompiler.analysis.VirtualRegister
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.backend.x64.setcc
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals

class X64Tests {
  private fun InstructionMap.assertIsSSA() {
    val defined = mutableSetOf<IRValue>()
    for ((_, instructions) in this) {
      for ((template, operands) in instructions) {
        require(operands.size == template.operandUse.size)
        val defs = operands
            .zip(template.operandUse)
            .filter {
              it.first !is MemoryReference && it.first !is ConstantValue &&
                  it.second == VariableUse.DEF || it.second == VariableUse.DEF_USE
            }
        for ((definedValue, _) in defs) {
          assert(definedValue !in defined) { "$definedValue already defined" }
          defined += definedValue
        }
      }
    }
  }

  private fun regAlloc(resourceName: String): AllocationResult {
    val cfg = prepareCFG(resource(resourceName), source)
    val gen = X64Generator(cfg)
    val instructionMap = gen.instructionSelection()
    instructionMap.assertIsSSA()
    val res = X64Target.regAlloc(cfg, instructionMap)
    val (newLists, allocation, _) = res
    for ((block, list) in newLists) {
      println(block)
      println(list.joinToString(separator = "\n", postfix = "\n"))
    }
    for ((value, register) in allocation) {
      println("allocate $value to $register")
    }
    println()
    val final = gen.applyAllocation(res)
    for ((block, list) in final) {
      println(block)
      println(list.joinToString(separator = "\n", postfix = "\n"))
    }
    return res
  }

  @Test
  fun `Instruction Selection On CFG`() {
    val cfg = prepareCFG(resource("codegen/addsAndMovs.c"), source)
    val iLists = X64Generator(cfg).instructionSelection()
    for (list in iLists) {
      println(list.value.joinToString(separator = "\n", postfix = "\n"))
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

  @Test
  fun `Register Allocation Inter-Block Interference`() {
    val (_, allocs, stackSlots) = regAlloc("codegen/interBlockInterference.c")
    assert(stackSlots.isEmpty())
    assertEquals(2, allocs.values.distinct().size)
  }
}
