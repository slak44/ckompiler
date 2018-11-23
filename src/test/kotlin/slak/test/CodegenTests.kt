package slak.test

import org.junit.Test
import slak.ckompiler.CodeGenerator

class CodegenTests {
  @Test
  fun basic() {
    val p = prepareCode("int main() {return 0;}", source)
    p.assertNoDiagnostics()
    val codegen = CodeGenerator(p.root)
    println(codegen.getNasm())
  }
}
