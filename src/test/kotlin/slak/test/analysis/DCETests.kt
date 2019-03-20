package slak.test.analysis

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.CondJump
import slak.test.*

class DCETests {
  private fun testDeadCode(code: String): Pair<IDebugHandler, CFG> {
    val p = prepareCode(code, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), analysisDh(code))
    analysisDh(code).diags.forEach { it.print() }
    return analysisDh(code) to cfg
  }

  @Test
  fun `Dead Code After Return`() {
    val code = """
      int main() {
        int x = 1;
        return x;
        int y = 2;
        y + 1;
      }
    """.trimIndent()
    val (dh) = testDeadCode(code)
    dh.assertDiags(DiagnosticId.UNREACHABLE_CODE, DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Terminal If`() {
    val code = """
      int main() {
        int x = 1;
        if (x < 1) return 7;
        else return 8;
        int dead = 265;
      }
    """.trimIndent()
    val (dh, cfg) = testDeadCode(code)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.terminator is CondJump)
    dh.assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Goto`() {
    val (dh) = testDeadCode(resource("gotoTest.c").readText())
    dh.assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }
}
