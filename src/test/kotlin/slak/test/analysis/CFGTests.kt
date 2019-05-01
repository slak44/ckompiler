package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.CondJump
import slak.ckompiler.analysis.ImpossibleJump
import slak.ckompiler.analysis.createGraphviz
import slak.test.*
import kotlin.test.assertEquals

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val text = resource("cfgTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 2`() {
    val text = resource("domsTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 3`() {
    val text = resource("domsTest2.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 4`() {
    val text = resource("domsTest3.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 5`() {
    val text = resource("domsTest4.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Graphviz CFG Creation Doesn't Fail`() {
    val text = resource("cfgTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
    createGraphviz(cfg, text, false)
  }

  @Test
  fun `Diamond Graph From If-Else Has Correct Dominance Frontier`() {
    val text = resource("trivialDiamondGraphTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    assert(cfg.startBlock.dominanceFrontier.isEmpty())
    val t = cfg.startBlock.terminator as CondJump
    val ret = cfg.nodes.first { it.terminator is ImpossibleJump }
    assertEquals(setOf(ret), t.target.dominanceFrontier)
    assertEquals(setOf(ret), t.other.dominanceFrontier)
  }

  @Test
  fun `Labels And Goto`() {
    val text = resource("gotoTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    CFG(p.root.decls.firstFun(), analysisDh(text))
  }

  @Test
  fun `Break And Continue`() {
    val text = resource("controlKeywordsTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `For Loop`() {
    val text = resource("forLoopTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    CFG(p.root.decls.firstFun(), analysisDh(text))
  }

  @Test
  fun `Early Return In Function`() {
    val text = resource("earlyReturnTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    CFG(p.root.decls.firstFun(), analysisDh(text))
  }

  @Test
  fun `Correct Definition Tracking Test`() {
    val text = resource("phiTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(text))
    for ((key, value) in cfg.definitions) {
      println("${key.first} (${key.second}) defined in \n\t${value.joinToString("\n\t")}")
    }
    assertEquals(3, cfg.definitions.size)
    val e = cfg.definitions.entries.toList()
    assertEquals(e[0].value.map { it.nodeId }, listOf(0, 3, 4, 9))
    assertEquals(e[1].value.map { it.nodeId }, listOf(0, 3, 4))
    assertEquals(e[2].value.map { it.nodeId }, listOf(0, 4))
  }
}
