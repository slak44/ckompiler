package slak.test.parser

import org.junit.Test
import slak.test.*

class ExpressionTests {
  @Test
  fun exprArithmPrecedence() {
    val p = prepareCode("int a = 1 + 2 * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3))) assertEquals p.root.getDeclarations()[0]
  }
}
