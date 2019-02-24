package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.BasicBlock.Companion.createGraphFor
import slak.ckompiler.analysis.CondJump
import slak.ckompiler.analysis.createGraphviz
import slak.test.*

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val p = prepareCode(resource("cfgTest.c").readText(), source)
    p.assertNoDiagnostics()
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.isTerminated())
  }

  @Test
  fun `Graphviz CFG Creation Doesn't Fail`() {
    val text = resource("cfgTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.isTerminated())
    createGraphviz(startBlock, text)
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
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    startBlock.assertHasDeadCode()
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
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.terminator is CondJump)
    val ifJmp = startBlock.terminator as CondJump
    ifJmp.target.assertHasDeadCode()
    ifJmp.other.assertHasDeadCode()
  }

  @Test
  fun `Labels And Goto`() {
    val p = prepareCode(resource("gotoTest.c").readText(), source)
    p.assertNoDiagnostics()
    createGraphFor(p.root.decls.firstFun())
  }

  @Test
  fun `Break And Continue`() {
    val p = prepareCode(resource("controlKeywordsTest.c").readText(), source)
    p.assertNoDiagnostics()
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.isTerminated())
  }
}
