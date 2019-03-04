package slak.test.analysis

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.warnDeadCode
import slak.test.*

class DCETests {
  private fun testDeadCode(code: String): IDebugHandler {
    val p = prepareCode(code, source)
    p.assertNoDiagnostics()
    val cfg = CFG(p.root.decls.firstFun(), false)
    cfg.exitBlock.collapseIfEmptyRecusively()
    analysisDh(code).warnDeadCode(cfg.startBlock)
    analysisDh(code).diags.forEach { it.print() }
    return analysisDh(code)
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
    val dh = testDeadCode(code)
    dh.assertDiags(DiagnosticId.UNREACHABLE_CODE, DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Goto`() {
    val dh = testDeadCode(resource("gotoTest.c").readText())
    dh.assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }
}
