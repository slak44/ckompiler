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

fun printMIDebug(target: X64Target, showDummies: Boolean, createCFG: () -> CFG) {
  val genInitial = X64Generator(createCFG(), target)
  val initialAlloc = genInitial.regAlloc(debugNoPostColoring = true, debugNoCheckAlloc = true)
  val (graph) = initialAlloc
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
  val realAllocation = gen.regAlloc(debugNoCheckAlloc = true)
  val finalGraph = realAllocation.graph
  for ((value, register) in realAllocation.allocations) {
    if (value is LoadableValue && value.isUndefined && !showDummies) continue
    println("allocate $value to $register")
  }
  printHeader("Allocation violations")
  initialAlloc.walkGraphAllocs { register, (block, index), type ->
    println("[$type] coloring violation for $register at (block $block, index $index)")
    println(graph[block][index].toString().lines().joinToString("\n") { "-->$it" })
    false
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
