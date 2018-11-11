package slak.test.parser

import org.junit.Test
import slak.test.assertNoDiagnostics
import slak.test.source

class FunctionsTests {
  @Test
  fun functionMain() {
    val p = prepareCode("int main() {}", source)
    p.assertNoDiagnostics()
  }
}
