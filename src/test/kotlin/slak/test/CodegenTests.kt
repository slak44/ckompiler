package slak.test

import org.junit.Test
import slak.ckompiler.analysis.CodeGenerator

class CodegenTests {
  @Test
  fun basic() {
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
