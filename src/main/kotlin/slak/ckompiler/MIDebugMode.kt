package slak.ckompiler

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.DerefStackValue
import slak.ckompiler.analysis.IRValue
import slak.ckompiler.analysis.LoadableValue
import slak.ckompiler.backend.*

fun generateMIDebug(
    target: MachineTarget<*>,
    srcFileName: String,
    srcText: String,
    showDummies: Boolean,
    generateHtml: Boolean,
    spillOutput: Boolean,
    createCFG: () -> CFG,
): String {
  return MIDebugMode(target, srcFileName, srcText, showDummies, generateHtml, spillOutput, createCFG).getOutput()
}

private class MIDebugMode(
    val target: MachineTarget<*>,
    val srcFileName: String,
    val srcText: String,
    val showDummies: Boolean,
    val generateHtml: Boolean,
    val spillOutput: Boolean,
    val createCFG: () -> CFG,
) {
  val text = StringBuilder()
  val document = createHTML()
  lateinit var body: BODY

  private fun buildHTMLWrapper(content: BODY.() -> Unit) {
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
        script(src = "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@10.2.0/build/languages/mipsasm.min.js") {}
        script {
          unsafe { +"hljs.initHighlightingOnLoad();" }
        }
        style {
          unsafe {
            +"""
              body, table {
                font-size: 16px !important;
                font-family: 'Fira Code', monospace !important;
                line-height: 1.4;
              }
            """.trimIndent()
            +"pre { margin: 0; }"
            +"code { white-space: pre-wrap; }"
            +"table { border-collapse: collapse; }"
            +"table + table { margin-top: 32px; }"
            +".alloc-table td { border-bottom: 1px solid #CCCCCC; }"
            +".left { text-align: left; }"
            +".right { text-align: right; }"
            +".arrow { padding: 0 8px; }"
            +".red { color: #F56764; }"
            +".code-table { margin-bottom: 16px; }"
            +".code-table code { padding: 0; }"
            +".code-table td:first-of-type { padding-right: 8px; border-right: 1px solid #CCCCCC; }"
            +".code-table td:nth-of-type(2) { margin-left: 8px; display: inline-block; }"
            +".code-table td:last-of-type { padding-left: 48px; }"
            +".code-table th:last-of-type { padding-left: 48px; }"
          }
        }
      }
      body(classes = "hljs") {
        this@MIDebugMode.body = this
        content()
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
        val highlight = when (target.isaType) {
          ISAType.X64 -> "x86asm"
          ISAType.MIPS32 -> "mipsasm"
        }
        code(classes = "language-$highlight", block)
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
    val inverted = allocs.entries.groupBy { it.value }.mapValues { entry -> entry.value.map { it.key } }

    if (generateHtml) {
      body.apply {
        table(classes = "alloc-table") {
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

        table(classes = "alloc-table") {
          thead {
            tr {
              th { +"MachineRegister" }
              th { +"" }
              th { +"IRValue" }
            }
          }
          tbody {
            for ((register, values) in inverted) {
              tr {
                td(classes = "right") { nasm { +(register.toString()) } }
                td(classes = "arrow") { +"←" }
                td { nasm { +(values.sortedBy { it.toString() }.joinToString("\n")) } }
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

  fun printBlock(block: InstrBlock) {
    if (generateHtml) {
      body.apply {
        table(classes = "code-table") {
          thead {
            tr {
              th()
              th(classes = "left") { +"MachineInstruction" }
              th(classes = "left") { +"Spill/Reload" }
            }
          }
          tbody {
            for ((idx, miCode) in block.map { it.toString() }.withIndex()) {
              tr {
                td(classes = "right") { +(idx.toString()) }
                td { nasm { +miCode } }
                td {
                  val maybeSpill = block[idx].defs.indexOfFirst { it is DerefStackValue }
                  val maybeReload = block[idx].uses.indexOfFirst { it is DerefStackValue }
                  if (maybeSpill >= 0) {
                    +"[spill ${block[idx].operands[1]}]"
                  }
                  if (maybeReload >= 0) {
                    +"[reload ${block[idx].operands[0]}]"
                  }
                }
              }
            }
          }
        }
      }
    } else {
      printNasm(block.joinToString(separator = "\n", postfix = "\n"))
    }
  }

  fun printGraph(instructionGraph: InstructionGraph) {
    for (blockId in instructionGraph.blocks - instructionGraph.returnBlock.id) {
      val block = instructionGraph[blockId]
      println(block)
      printNasm(block.phi.entries.joinToString(separator = "\n") { (variable, incoming) ->
        val incStr = incoming.entries.joinToString { (blockId, variable) -> "BB$blockId ${variable.versionString()}" }
        "$variable ← φ($incStr)"
      })
      printBlock(block)
    }
  }

  fun printError(string: String) {
    if (generateHtml) {
      body.apply {
        pre(classes = "red") {
          +string
        }
      }
    } else {
      text.append(string)
      text.append('\n')
    }
  }

  fun getOutput(): String {
    buildHTMLWrapper {
      try {
        generateMIDebugInternal()
      } catch (e: Exception) {
        // Ignore errors, print as much as possible + the error
        printHeader("Debug mode error. Stopping here.")
        printError(e.stackTraceToString())
      }
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
  printTitle("Allocation for function ${cfgInit.functionIdentifier}")
  val genInitial = createTargetFunGenerator(cfgInit, target)

  if (spillOutput) {
    printHeader("Initial MachineInstructions (no alloc)")
    printGraph(genInitial.graph)

    val pressure = genInitial.findRegisterPressure()
    val maxPressure = target.maxPressure
    printHeader("Register pressure")
    for ((regClass, pressureMap) in pressure) {
      println("Class: $regClass")
      val byBlock = pressureMap.keys.groupBy { it.first }
      for ((blockId, locations) in byBlock) {
        val ordered = locations.sortedBy { it.second }
        for (location in ordered) {
          printNasm(genInitial.graph[blockId][location.second].toString())
          val maxHere = maxPressure[regClass] ?: Int.MAX_VALUE
          val pressureHere = pressureMap.getValue(location)
          if (pressureHere > maxHere) {
            printError("pressure (max is $maxHere): $pressureHere")
          } else {
            println("pressure: $pressureHere")
          }
        }
      }
      println()
    }
  }

  val initialAlloc = genInitial.regAlloc(debugNoPostColoring = true, debugNoCheckAlloc = true)
  val (graph) = initialAlloc
  printHeader("Initial MachineInstructions (with parallel copies)")
  printGraph(graph)

  printHeader("Register allocation")
  val gen = createTargetFunGenerator(createCFG(), target)
  val realAllocation = gen.regAlloc(debugNoCheckAlloc = true)
  val finalGraph = realAllocation.graph
  val allocs = realAllocation.allocations.filter { (value) ->
    value is LoadableValue && (!value.isUndefined || showDummies)
  }
  printAllocs(allocs)

  printHeader("Allocation violations")
  val didFindAny = initialAlloc.walkGraphAllocs { register, (block, index), type ->
    println("[$type] coloring violation for $register at (block $block, index $index)")
    printNasm(graph[block][index].toString().lines().joinToString("\n") { "-->$it" })
    true
  }
  if (!didFindAny) println("No violations found :)")

  printHeader("Processed MachineInstructions (with applied allocation)")
  val final = gen.applyAllocation(realAllocation)
  for ((blockId, list) in final) {
    println(finalGraph[blockId])
    printNasm(list.joinToString(separator = "\n", postfix = "\n"))
  }
  printHeader("Optimized MachineInstructions")
  for ((blockId, list) in final) {
    val withOpts = createPeepholeOptimizer(target.isaType).optimize(gen, list)
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
