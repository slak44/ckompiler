package slak.test.backend

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.backend.x64.X64TargetOpts
import slak.ckompiler.backend.x64.setcc
import slak.ckompiler.parser.SignedIntType
import slak.ckompiler.parser.TypedIdentifier
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals

class X64Tests {
  private fun InstructionGraph.assertIsSSA() {
    val defined = mutableMapOf<IRValue, Int>()
    for (blockId in domTreePreorder) {
      for (mi in this[blockId]) {
        for (definedValue in mi.defs) {
          assert(definedValue !in defined || defined.getValue(definedValue) == mi.irLabelIndex) {
            "$definedValue already defined"
          }
          defined[definedValue] = mi.irLabelIndex
        }
      }
    }
  }

  private fun regAllocCode(code: String): AllocationResult {
    val cfg = prepareCFG(code, source)
    return regAlloc(cfg)
  }

  private fun regAlloc(resourceName: String): AllocationResult {
    val cfg = prepareCFG(resource(resourceName), source)
    return regAlloc(cfg)
  }

  private object DefaultX64TestOptions : TargetOptions {
    override val omitFramePointer = false
  }

  private fun regAlloc(
      cfg: CFG,
      baseTargetOptions: TargetOptions = DefaultX64TestOptions,
      targetOptions: List<String> = emptyList()
  ): AllocationResult {
    val target = X64Target(X64TargetOpts(baseTargetOptions, targetOptions, cfg))
    val gen = X64Generator(cfg, target)
    gen.graph.assertIsSSA()
    val res = gen.regAlloc()
    val (graph, allocation, _) = res
    for (blockId in graph.domTreePreorder) {
      println(graph[blockId])
      println(graph[blockId].joinToString(separator = "\n", postfix = "\n"))
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

  @Suppress("unused")
  enum class RegisterCalleeSavedTestCase(val reg: MachineRegister, val isCalleeSaved: Boolean) {
    RAX(X64Target().registerByName("rax"), false),
    RBX(X64Target().registerByName("rbx"), true),
    RSP(X64Target().registerByName("rsp"), true),
    R10(X64Target().registerByName("r10"), false),
    XMM5(X64Target().registerByName("xmm5"), false),
    STACK_SLOT(StackSlot(
        StackVariable(TypedIdentifier("fake", SignedIntType)),
        MachineTargetData.x64
    ), false)
  }

  @ParameterizedTest
  @EnumSource(RegisterCalleeSavedTestCase::class)
  fun `Correctly Detect Callee Saved Registers`(test: RegisterCalleeSavedTestCase) {
    val reported = X64Target().isPreservedAcrossCalls(test.reg)
    assertEquals(test.isCalleeSaved, reported)
  }

  @Test
  fun `Instruction Selection On CFG`() {
    val cfg = prepareCFG(resource("codegen/addsAndMovs.c"), source)
    val gen = X64Generator(cfg, X64Target())
    for (blockId in gen.graph.domTreePreorder) {
      println(gen.graph[blockId].joinToString(separator = "\n", postfix = "\n"))
    }
  }

  @Test
  fun `Register Allocation`() {
    val (_, allocs) = regAlloc("codegen/interference.c")
    assertEquals(3, allocs.values.distinct().size)
  }

  @Test
  fun `Register Allocation Lots Of Unused Variables`() {
    val values = (1..30).joinToString("\n") { "int var$it = 0;" }
    val (_, allocs) = regAllocCode("""
      int main() { $values return 0; }
    """.trimIndent())
    assertEquals(1, allocs.values.distinct().size)
  }

  @ParameterizedTest
  @ValueSource(ints = [2, 3, 4, 5, 6])
  fun `Register Allocation With Int Function Arguments In Registers`(argCount: Int) {
    val args = (1..argCount).joinToString(", ") { "int arg$it" }
    val (_, allocs) = regAllocCode("""
      int f($args) { return arg1; }
    """.trimIndent())
    assertEquals(2, allocs.values.distinct().size)
  }

  @Test
  fun `Register Allocation RCX Parameter Not Clobbered`() {
    val (_, allocs) = regAllocCode("""
      int f(int argRDI, int argRSI, int argRDX, int argRCX) {
        // argRDX will try to be moved to rcx, which should not happen
        int fakeUse = argRDI + argRSI + argRDX + argRCX;
        return 0;
      }
    """.trimIndent())
    val variable = allocs.keys.first { it.name == "argRDX" }
    assert(allocs.getValue(variable).regName != "rcx")
  }

  @Test
  fun `Register Allocation With Varying Sizes (SETcc Instruction)`() {
    val (graph, _) = regAlloc("codegen/storeCmp.c")
    val setInstr = graph[graph.startId].first { it.template in setcc.getOrElse(it.template.name, ::emptyList) }
    assertEquals(1, setInstr.operands.size)
    assert(setInstr.operands[0] is VirtualRegister)
    assertEquals(1, MachineTargetData.x64.sizeOf(setInstr.operands[0].type))
  }

  @Test
  fun `Register Allocation With Register Pressure`() {
    val result = regAlloc("codegen/highRegisterPressure.c")
    val allocs = result.allocations
    assertEquals(14, allocs.values.filter { it.valueClass != Memory }.distinct().size)
    val spilled = allocs.entries.filter { it.value.valueClass == Memory }
    assertEquals(1, spilled.size)
    assertEquals(1, result.stackSlots.size)
  }

  @Test
  fun `Register Allocation Many Non-Interfering`() {
    val result = regAlloc("codegen/parallelLiveRanges.c")
    assertEquals(0, result.allocations.values.filter { it.valueClass == Memory }.size)
    assert(result.stackSlots.isEmpty())
  }

  // FIXME: this is a terrible test
  @Test
  fun `Register Allocation Inter-Block Interference`() {
    val result = regAlloc("codegen/interBlockInterference.c")
    assert(result.stackSlots.isEmpty())
    assertEquals(2, result.allocations.values.distinct().size)
  }
}
