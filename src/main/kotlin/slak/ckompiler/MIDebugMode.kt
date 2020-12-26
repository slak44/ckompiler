package slak.ckompiler

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.IRValue
import slak.ckompiler.analysis.LoadableValue
import slak.ckompiler.backend.MachineRegister
import slak.ckompiler.backend.findRegisterPressure
import slak.ckompiler.backend.regAlloc
import slak.ckompiler.backend.walkGraphAllocs
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Instruction
import slak.ckompiler.backend.x64.X64PeepholeOpt
import slak.ckompiler.backend.x64.X64Target
import java.lang.Exception

fun generateMIDebug(
    target: X64Target,
    srcFileName: String,
    srcText: String,
    showDummies: Boolean,
    generateHtml: Boolean,
    spillOutput: Boolean,
    createCFG: () -> CFG
): String {
  return MIDebugMode(target, srcFileName, srcText, showDummies, generateHtml, spillOutput, createCFG).getOutput()
}

private class MIDebugMode(
    val target: X64Target,
    val srcFileName: String,
    val srcText: String,
    val showDummies: Boolean,
    val generateHtml: Boolean,
    val spillOutput: Boolean,
    val createCFG: () -> CFG
) {
  val text = StringBuilder()
  val document = createHTML()
  lateinit var body: BODY

  init {
    document.html {
      head {
        title(srcFileName.takeLastWhile { it != '/' })
        link(
            rel = "stylesheet",
            href = "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@10.2.0/build/styles/darcula.min.css"
        )
        script(src = "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@10.2.0/build/highlight.min.js") {}
        script(src = "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@10.2.0/build/languages/c.min.js") {}
        script(src = "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@10.2.0/build/languages/x86asm.min.js") {}
        script {
          unsafe { +"hljs.initHighlightingOnLoad();" }
        }
        style {
          unsafe {
            +"pre { margin: 0; }"
            +"code { white-space: pre-wrap; }"
            +"body { font-size: 16px !important; font-family: 'Fira Code', monospace !important; }"
            +"table { border-collapse: collapse; }"
            +"td { border-bottom: 1px solid #CCCCCC; }"
            +".right { text-align: right; }"
            +".arrow { padding: 0 8px; }"
          }
        }
      }
      body(classes = "hljs") {
        this@MIDebugMode.body = this
      }
    }
  }

  fun println() {
    if (generateHtml) {
      body.apply { br() }
    } else {
      text.append('\n')
    }
  }

  fun println(any: Any) {
    if (generateHtml) {
      body.apply {
        +(any.toString())
        br()
      }
    } else {
      text.append(any.toString())
      text.append('\n')
    }
  }

  fun printHeader(text: String) {
    if (generateHtml) {
      body.apply {
        h2 { +text }
      }
    } else {
      println()
      println(text)
      println("-".repeat(text.length))
      println()
    }
  }

  fun printTitle(text: String) {
    if (generateHtml) {
      body.apply {
        h1 { +text }
      }
    } else {
      println()
      println(text)
      println("=".repeat(text.length))
      println()
    }
  }

  fun FlowContent.nasm(block: CODE.() -> Unit) {
    apply {
      pre {
        code(classes = "language-x86asm", block)
      }
    }
  }

  fun printNasm(text: String?) {
    if (text.isNullOrBlank()) return
    if (generateHtml) {
      body.nasm { +text }
    } else {
      println(text)
    }
  }

  fun printAllocs(allocs: Map<IRValue, MachineRegister>) {
    if (generateHtml) {
      body.apply {
        table {
          thead {
            tr {
              th { +"IRValue" }
              th { +"" }
              th { +"MachineRegister" }
            }
          }
          tbody {
            for ((value, register) in allocs) {
              tr {
                td(classes = "right") { nasm { +(value.toString()) } }
                td(classes = "arrow") { +"→" }
                td { nasm { +(register.toString()) } }
              }
            }
          }
        }
      }
    } else {
      for ((value, register) in allocs) {
        println("allocate $value to $register")
      }
    }
  }

  fun getOutput(): String {
    try {
      generateMIDebugInternal()
    } catch (e: Exception) {
      e.printStackTrace()
      // Ignore errors, just try and print as much as possible
    }

    return if (generateHtml) {
      document.finalize()
    } else {
      text.toString()
    }
  }
}

private fun MIDebugMode.generateMIDebugInternal() {
  val cfgInit = createCFG()
  printTitle("Allocation for function ${cfgInit.f.name} of type ${cfgInit.f.functionType}")
  val genInitial = X64Generator(cfgInit, target)

  if (spillOutput) {
    val pressure = genInitial.findRegisterPressure()
    printHeader("Register pressure")
    for ((regClass, pressureMap) in pressure) {
      println("Class: $regClass")
      val byBlock = pressureMap.keys.groupBy { it.first }
      for ((blockId, locations) in byBlock) {
        val ordered = locations.sortedBy { it.second }
        for (location in ordered) {
          printNasm(genInitial.graph[blockId][location.second].toString())
          println("pressure: ${pressureMap[location]}")
        }
      }
      println()
    }
  }

  val initialAlloc = genInitial.regAlloc(debugNoPostColoring = true, debugNoCheckAlloc = true)
  val (graph) = initialAlloc
  printHeader("Initial MachineInstructions (with parallel copies)")
  for (blockId in graph.blocks - graph.returnBlock.id) {
    val block = graph[blockId]
    println(block)
    printNasm(block.phi.entries.joinToString(separator = "\n") { (variable, incoming) ->
      val incStr = incoming.entries.joinToString { (blockId, variable) -> "n$blockId v${variable.version}" }
      "$variable ← φ($incStr)"
    })
    printNasm(block.joinToString(separator = "\n", postfix = "\n"))
  }
  printHeader("Register allocation")
  val gen = X64Generator(createCFG(), target)
  val realAllocation = gen.regAlloc(debugNoCheckAlloc = true)
  val finalGraph = realAllocation.graph
  val allocs = realAllocation.allocations.filter { (value) ->
    value is LoadableValue && (!value.isUndefined || showDummies)
  }
  printAllocs(allocs)
  printHeader("Allocation violations")
  initialAlloc.walkGraphAllocs { register, (block, index), type ->
    println("[$type] coloring violation for $register at (block $block, index $index)")
    printNasm(graph[block][index].toString().lines().joinToString("\n") { "-->$it" })
    false
  }
  printHeader("Processed MachineInstructions (with applied allocation)")
  val final = gen.applyAllocation(realAllocation)
  for ((blockId, list) in final) {
    println(finalGraph[blockId])
    printNasm(list.joinToString(separator = "\n", postfix = "\n"))
  }
  printHeader("Optimized MachineInstructions")
  for ((blockId, list) in final) {
    @Suppress("UNCHECKED_CAST")
    val withOpts = X64PeepholeOpt().optimize(gen, list as List<X64Instruction>)
    println(finalGraph[blockId])
    printNasm(withOpts.joinToString(separator = "\n", postfix = "\n"))
    println("(initial: ${list.size} | optimized: ${withOpts.size})")
    println()
  }
  if (generateHtml) {
    printHeader("Original source")
    body.apply {
      pre {
        code(classes = "language-c") {
          +srcText
        }
      }
    }
  }
}
