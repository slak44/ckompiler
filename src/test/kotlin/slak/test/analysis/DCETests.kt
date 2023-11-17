package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.analysis.CondJump
import slak.test.*

class DCETests {
  @Test
  fun `Dead Code After Return`() {
    val factory = prepareCFG("""
      int main() {
        int x = 1;
        return x;
        int y = 2;
        y + 1;
      }
    """.trimIndent(), source)
    factory.create()
    factory.assertDiags(DiagnosticId.UNREACHABLE_CODE, DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Terminal If`() {
    val factory = prepareCFG("""
      int main() {
        int x = 1;
        if (x < 1) return 7;
        else return 8;
        int dead = 265;
      }
    """.trimIndent(), source)
    val cfg = factory.create()
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.terminator is CondJump)
    factory.assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Goto`() {
    val factory = prepareCFG(resource("dce/gotoTest.c"), source)
    factory.create()
    factory.assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Live Code After Return`() {
    val factory = prepareCFG(resource("dce/liveCodeAfterReturnTest.c"), source)
    factory.create()
    factory.assertNoDiagnostics()
  }
}
