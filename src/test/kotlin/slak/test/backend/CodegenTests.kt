package slak.test.backend

import org.junit.Test
import slak.ckompiler.backend.CodeGenerator
import slak.test.assertNoDiagnostics
import slak.test.prepareCode
import slak.test.source

class CodegenTests {
  @Test
  fun `Main That Returns 0`() {
    val p = prepareCode("int main() { return 0; }", source)
    p.assertNoDiagnostics()
  }
}
