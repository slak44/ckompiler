package slak.test.analysis

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.warnDeadCode
import slak.test.*

class DCETests {
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
    val p = prepareCode(code, source)
    p.assertNoDiagnostics()
    analysisDh(code).warnDeadCode(BasicBlock.createGraphFor(p.root.decls.firstFun()))
    analysisDh(code).diags.forEach { it.print() }
    analysisDh(code).assertDiags(DiagnosticId.UNREACHABLE_CODE, DiagnosticId.UNREACHABLE_CODE)
  }

  @Test
  fun `Dead Code After Goto`() {
    val code = resource("gotoTest.c").readText()
    val p = prepareCode(code, source)
    p.assertNoDiagnostics()
    analysisDh(code).warnDeadCode(BasicBlock.createGraphFor(p.root.decls.firstFun()))
    analysisDh(code).diags.forEach { it.print() }
    analysisDh(code).assertDiags(DiagnosticId.UNREACHABLE_CODE)
  }
}
