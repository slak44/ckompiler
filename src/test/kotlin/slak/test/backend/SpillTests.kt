package slak.test.backend

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.analysis.DerefStackValue
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64RegisterClass
import slak.ckompiler.backend.x64.X64Target
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SpillTests {
  private fun SpillResult.assertNoSpills() {
    val spills = mutableListOf<Location>()
    val reloads = mutableListOf<Location>()
    for ((blockSpills, blockReloads) in values) {
      spills += blockSpills
      reloads += blockReloads
    }
    assert(spills.isEmpty()) { "There are spills: $spills" }
    assert(reloads.isEmpty()) { "There are reloads: $reloads" }
  }

  @Test
  fun `No Register Pressure, Integer`() {
    val cfg = prepareCFG(resource("spilling/noPressure.c"), source)
    X64Generator(cfg, X64Target()).runSpiller().assertNoSpills()
  }

  @Test
  fun `No Register Pressure, SSE`() {
    val cfg = prepareCFG(resource("spilling/floatNoPressure.c"), source)
    X64Generator(cfg, X64Target()).runSpiller().assertNoSpills()
  }

  @Test
  fun `No Register Pressure, Full Occupancy`() {
    val cfg = prepareCFG(resource("spilling/noPressureLimit.c"), source)
    X64Generator(cfg, X64Target()).runSpiller().assertNoSpills()
  }

  @Test
  fun `One Int Pressure`() {
    val cfg = prepareCFG(resource("spilling/oneIntPressure.c"), source)
    val target = X64Target()
    val gen = X64Generator(cfg, target)
    val spillResult = gen.runSpiller()
    assert(spillResult.isNotEmpty()) { "Nothing was spilled" }
    val (spills, reloads) = assertNotNull(spillResult[gen.graph.startId])
    assertEquals(1, spills.size)
    assertEquals(1, reloads.size)
  }

  @ParameterizedTest
  @ValueSource(ints = [1, 2, 5, 10, 20, 30])
  fun `Spill Lots Of Ints`(toSpill: Int) {
    val target = X64Target()
    val k = (target.registers - target.forbidden).count { it.valueClass == X64RegisterClass.INTEGER }
    val names = (0 until (k + toSpill)).map { "x$it" }
    val cfg = prepareCFG("""
      int main() {
        int ${names.joinToString(" = 1, ", postfix = " = 1")};
        int sum = ${names.joinToString(" + ")};
        return sum;
      }
    """.trimIndent(), source)
    val gen = X64Generator(cfg, target)
    val spillResult = gen.runSpiller()
    assert(spillResult.isNotEmpty()) { "Nothing was spilled" }
    val (spills, reloads) = assertNotNull(spillResult[gen.graph.startId])
    assertEquals(toSpill, spills.size)
    assertEquals(toSpill, reloads.size)
  }

  @Test
  fun `No Register Pressure With If, Integer`() {
    val cfg = prepareCFG(resource("spilling/ifNoSpill.c"), source)
    X64Generator(cfg, X64Target()).runSpiller().assertNoSpills()
  }

  @Test
  fun `No Register Pressure With Loop, Integer`() {
    val cfg = prepareCFG(resource("spilling/noSpillLoop.c"), source)
    X64Generator(cfg, X64Target()).runSpiller().assertNoSpills()
  }

  @Test
  fun `No Register Pressure With Function Call`() {
    val cfg = prepareCFG(resource("spilling/noSpillCall.c"), source)
    X64Generator(cfg, X64Target()).runSpiller().assertNoSpills()
  }

  @Test
  fun `Live-In Value Isn't Reloaded`() {
    val cfg = prepareCFG(resource("spilling/noReloadLiveIn.c"), source)
    X64Generator(cfg, X64Target()).runSpiller().assertNoSpills()
  }

  @Test
  fun `Spill Int With Constrained`() {
    val cfg = prepareCFG(resource("e2e/spillWithConstrained.c"), source)
    val target = X64Target()
    val gen = X64Generator(cfg, target)
    val spillResult = gen.runSpiller()
    gen.insertSpillReloadCode(spillResult, mutableMapOf())
    assert(spillResult.isNotEmpty()) { "Nothing was spilled" }
    val (spills, _) = assertNotNull(spillResult[gen.graph.startId])
    assert(spills.size >= 3) { "Not enough spills" }
  }

  @Test
  fun `Overconstrained Function Call`() {
    val cfg = prepareCFG(resource("spilling/overconstrainedFunCall.c"), source, "main")
    val target = X64Target()
    val gen = X64Generator(cfg, target)
    val spillResult = gen.runSpiller()
    gen.insertSpillReloadCode(spillResult, mutableMapOf())
    assert(spillResult.isNotEmpty()) { "Nothing was spilled" }
    assertNotNull(spillResult[gen.graph.startId])
  }

  @Test
  fun `Spill Creates Phi With Memory Operands`() {
    val cfg = prepareCFG(resource("spilling/phiWithMemory.c"), source)
    val target = X64Target()
    val gen = X64Generator(cfg, target)
    val spillResult = gen.runSpiller()
    gen.insertSpillReloadCode(spillResult, mutableMapOf())
    val (ifTrue, ifFalse) = gen.graph.successors(gen.graph.startId).toList()
    val (spills, _) = assertNotNull(spillResult[ifFalse.id])
    assert(spills.any { it.first.name == "spilled" } && spills.any { it.first.name == "r14" }) {
      "Must spill spilled and r14; actually spilled: ${spills.map { it.first }}"
    }
    val (finalBlock) = gen.graph.successors(ifTrue).toList()
    assert(finalBlock.phi.entries.any { (target, incoming) ->
      target.name == "spilled" && incoming.values.any { it is DerefStackValue }
    }) {
      "Variable \"spilled\" must have a stack value on one φ branch"
    }
  }

  @Test
  fun `Spill Creates Phi With Only Memory Operands`() {
    val cfg = prepareCFG(resource("spilling/phiWithDoubleMemory.c"), source)
    val target = X64Target()
    val gen = X64Generator(cfg, target)
    val spillResult = gen.runSpiller()
    gen.insertSpillReloadCode(spillResult, mutableMapOf())
    val (ifTrue, ifFalse) = gen.graph.successors(gen.graph.startId).toList()
    val (spills, _) = assertNotNull(spillResult[ifFalse.id])
    assert(spills.any { it.first.name == "spilled" } && spills.any { it.first.name == "r14" }) {
      "Must spill spilled, r14; actually spilled: ${spills.map { it.first }}"
    }
    val (finalBlock) = gen.graph.successors(ifTrue).toList()
    assert(finalBlock.phi.entries.any { (target, incoming) ->
      target.name == "spilled" && incoming.values.all { it is DerefStackValue }
    }) {
      "Variable \"spilled\" must have a stack value on all φ branches"
    }
  }

  @Test
  fun `Removing Spilled Value From Parallel Copy Works`() {
    val cfg = prepareCFG(resource("spilling/removeSpillParallel.c"), source)
    val target = X64Target()
    val gen = X64Generator(cfg, target)
    gen.regAlloc(debugReturnAfterSpill = true)
  }
}
