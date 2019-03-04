package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.CondJump
import slak.ckompiler.analysis.createGraphviz
import slak.test.*

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val p = prepareCode(resource("cfgTest.c").readText(), source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), true)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 2`() {
    val p = prepareCode(resource("domsTest.c").readText(), source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), true)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 3`() {
    val p = prepareCode(resource("domsTest2.c").readText(), source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), true)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Graphviz CFG Creation Doesn't Fail`() {
    val text = resource("cfgTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), true)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
    createGraphviz(cfg, text, false)
  }

  @Test
  fun `Dead Code After Return`() {
    val p = prepareCode("""
      int main() {
        int x = 1;
        return x;
        int y = 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), false)
    assert(cfg.startBlock.data.isNotEmpty())
    cfg.startBlock.assertHasDeadCode()
  }

  @Test
  fun `Dead Code After Terminal If`() {
    val p = prepareCode("""
      int main() {
        int x = 1;
        if (x < 1) return 7;
        else return 8;
        int dead = 265;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), false)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.terminator is CondJump)
    val ifJmp = cfg.startBlock.terminator as CondJump
    ifJmp.target.assertHasDeadCode()
    ifJmp.other.assertHasDeadCode()
  }

  @Test
  fun `Labels And Goto`() {
    val p = prepareCode(resource("gotoTest.c").readText(), source)
    p.assertNoDiagnostics()
    CFG(p.root.decls.firstFun(), false)
  }

  @Test
  fun `Break And Continue`() {
    val p = prepareCode(resource("controlKeywordsTest.c").readText(), source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), false)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }
}
