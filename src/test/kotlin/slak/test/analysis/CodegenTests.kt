package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.CodeGenerator
import slak.test.assertNoDiagnostics
import slak.test.prepareCode
import slak.test.source

class CodegenTests {
  @Test
  fun `Basic`() {
    val p = prepareCode("""
      int main() {
        int a = 1;
        return 0;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val codegen = CodeGenerator(p.root)
    println(codegen.getNasm())
  }
}
