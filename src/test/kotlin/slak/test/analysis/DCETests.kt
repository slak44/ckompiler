package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.analysis.CondJump
import slak.test.*

class DCETests {
  @Test
  fun `Dead Code After Return`() {
    val cfg = prepareCFG("""
      int main() {
        int x = 1;
        return x;
        int y = 2;
        y + 1;
      }
    """.trimIndent(), source)
    cfg.assertDiags(DiagnosticId.UNREACHABLE_CODE, DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Terminal If`() {
    val cfg = prepareCFG("""
      int main() {
        int x = 1;
        if (x < 1) return 7;
        else return 8;
        int dead = 265;
      }
    """.trimIndent(), source)
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.terminator is CondJump)
    cfg.assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Goto`() {
    val cfg = prepareCFG(resource("dce/gotoTest.c"), source)
    cfg.assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Live Code After Return`() {
    val cfg = prepareCFG(resource("dce/liveCodeAfterReturnTest.c"), source)
    cfg.assertNoDiagnostics()
  }
}
