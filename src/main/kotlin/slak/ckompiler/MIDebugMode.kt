package slak.ckompiler

import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.LoadableValue
import slak.ckompiler.backend.regAlloc
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Instruction
import slak.ckompiler.backend.x64.X64PeepholeOpt
import slak.ckompiler.backend.x64.X64Target

fun printMIDebug(cfg: CFG, target: X64Target, showDummies: Boolean) {
  val gen = X64Generator(cfg, target)
  val selected = gen.instructionSelection()
  val (debugInstrs) = gen.regAlloc(selected, debugNoReplaceParallel = true)
  for ((block, list) in debugInstrs) {
    println(block)
    println(list.joinToString(separator = "\n", postfix = "\n"))
  }
  val realAllocation = gen.regAlloc(selected)
  for ((value, register) in realAllocation.allocations) {
    if (value is LoadableValue && value.isUndefined && !showDummies) continue
    println("allocate $value to $register")
  }
  println()
  val final = gen.applyAllocation(realAllocation)
  for ((block, list) in final) {
    println(block)
    println(list.joinToString(separator = "\n", postfix = "\n"))
  }
  println("Optimized:")
  for ((block, list) in final) {
    @Suppress("UNCHECKED_CAST")
    val withOpts = X64PeepholeOpt().optimize(gen, list as List<X64Instruction>)
    println(block)
    println(withOpts.joinToString(separator = "\n", postfix = "\n"))
    println("(initial: ${list.size} | optimized: ${withOpts.size})")
    println()
  }
}
