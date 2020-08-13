package slak.ckompiler

import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Instruction
import slak.ckompiler.backend.x64.X64PeepholeOpt
import slak.ckompiler.backend.x64.X64Target

private fun printHeader(text: String) {
  println()
  println(text)
  println("-".repeat(text.length))
  println()
}

private fun printNotBlank(text: String?) {
  if (text.isNullOrBlank()) return
  println(text)
}

enum class ViolationType {
  HARD, SOFT
}

inline fun AllocationResult.walkGraphAllocs(violationHandler: (MachineRegister, InstrLabel, ViolationType) -> Unit) {
  val allocated = allocations.keys.filterIsInstance<AllocatableValue>()
  for (blockId in graph.blocks) {
    val alive = mutableListOf<MachineRegister>()
    alive += graph.liveInsOf(blockId).map { allocations.getValue(it) }
    for (dyingPhiVarReg in graph[blockId].phi.values.flatMap { it.values }.map { allocations.getValue(it) }) {
      alive -= dyingPhiVarReg
    }
    alive += graph[blockId].phi.keys.map { allocations.getValue(it) }
    for ((index, mi) in graph[blockId].withIndex()) {
      val regsDefinedHere = mi.defs.intersect(allocated).map { allocations.getValue(it) } +
          mi.defs.filterIsInstance<PhysicalRegister>().map { it.reg }
      val regsDyingHere = mi.uses.intersect(allocated)
          .filter { graph.isLastUse(it as AllocatableValue, InstrLabel(blockId, index)) }
          .map { allocations.getValue(it) } +
          mi.uses.filterIsInstance<PhysicalRegister>().map { it.reg }
      val useDefRegs = mi
          .filterOperands { _, variableUse -> variableUse == VariableUse.DEF_USE }
          .map { allocations.getValue(it) }
      alive -= regsDyingHere
      for (definedHere in regsDefinedHere) {
        if (definedHere in alive) {
          val violationType = if (definedHere !in useDefRegs) ViolationType.HARD else ViolationType.SOFT
          violationHandler(definedHere, InstrLabel(blockId, index), violationType)
        }
      }
      alive += regsDefinedHere
    }
  }
}

fun printMIDebug(target: X64Target, showDummies: Boolean, createCFG: () -> CFG) {
  val genInitial = X64Generator(createCFG(), target)
  val (graph) = genInitial.regAlloc(debugNoReplaceParallel = true)
  printHeader("Initial MachineInstructions (with parallel copies)")
  for (blockId in graph.blocks - graph.returnBlock.id) {
    val block = graph[blockId]
    println(block)
    printNotBlank(block.phi.entries.joinToString(separator = "\n") { (variable, incoming) ->
      val incStr = incoming.entries.joinToString { (blockId, variable) -> "n$blockId v${variable.version}" }
      "$variable ← φ($incStr)"
    })
    printNotBlank(block.joinToString(separator = "\n", postfix = "\n"))
  }
  printHeader("Register allocation")
  val gen = X64Generator(createCFG(), target)
  val realAllocation = gen.regAlloc()
  val finalGraph = realAllocation.graph
  for ((value, register) in realAllocation.allocations) {
    if (value is LoadableValue && value.isUndefined && !showDummies) continue
    println("allocate $value to $register")
  }
  printHeader("Allocation violations")
  realAllocation.walkGraphAllocs { register, (block, index), type ->
    if (type == ViolationType.SOFT) {
      print("[SOFT] ")
    }
    println("coloring violation for $register at (block $block, index $index)")
    println(finalGraph[block][index].toString().lines().joinToString("\n") { "-->$it" })
  }
  printHeader("Processed MachineInstructions (with applied allocation)")
  val final = gen.applyAllocation(realAllocation)
  for ((blockId, list) in final) {
    println(finalGraph[blockId])
    printNotBlank(list.joinToString(separator = "\n", postfix = "\n"))
  }
  printHeader("Optimized MachineInstructions")
  for ((blockId, list) in final) {
    @Suppress("UNCHECKED_CAST")
    val withOpts = X64PeepholeOpt().optimize(gen, list as List<X64Instruction>)
    println(finalGraph[blockId])
    printNotBlank(withOpts.joinToString(separator = "\n", postfix = "\n"))
    println("(initial: ${list.size} | optimized: ${withOpts.size})")
    println()
  }
}
