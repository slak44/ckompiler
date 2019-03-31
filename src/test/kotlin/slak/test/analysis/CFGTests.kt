package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.CondJump
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.parser.ReturnStatement
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
    val ret = cfg.nodes.first { it.data.last() is ReturnStatement }
    assertEquals(setOf(ret), t.target.dominanceFrontier)
    assertEquals(setOf(ret), t.other.dominanceFrontier)
    assert(cfg.exitBlock.dominanceFrontier.isEmpty())
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
}
