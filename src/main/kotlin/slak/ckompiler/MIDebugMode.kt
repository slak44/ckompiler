package slak.ckompiler

import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.LoadableValue
import slak.ckompiler.backend.regAlloc
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
  val (graph) = genInitial.regAlloc(debugNoReplaceParallel = true)
  printHeader("Initial MachineInstructions (with parallel copies)")
  for (blockId in graph.domTreePreorder) {
    val block = graph[blockId]
    println(block)
    printNotBlank(block.phi.entries.joinToString(separator = "\n") { (variable, incoming) ->
      val incStr = incoming.entries.joinToString { (blockId, variable) -> "n$blockId v${variable.id}" }
      "$variable ← φ($incStr)"
    })
    printNotBlank(block.joinToString(separator = "\n", postfix = "\n"))
  }
  printHeader("Register allocation")
  val gen = X64Generator(createCFG(), target)
  val realAllocation = gen.regAlloc()
  for ((value, register) in realAllocation.allocations) {
    if (value is LoadableValue && value.isUndefined && !showDummies) continue
    println("allocate $value to $register")
  }
  printHeader("Processed MachineInstructions (with applied allocation)")
  val final = gen.applyAllocation(realAllocation)
  for ((block, list) in final) {
    println(block)
    printNotBlank(list.joinToString(separator = "\n", postfix = "\n"))
  }
  printHeader("Optimized MachineInstructions")
  for ((block, list) in final) {
    @Suppress("UNCHECKED_CAST")
    val withOpts = X64PeepholeOpt().optimize(gen, list as List<X64Instruction>)
    println(block)
    printNotBlank(withOpts.joinToString(separator = "\n", postfix = "\n"))
    println("(initial: ${list.size} | optimized: ${withOpts.size})")
    println()
  }
}
