package slak.test.parser

import org.junit.Test
import slak.ckompiler.Operators
import slak.test.*

class ExpressionTests {
  @Test
  fun exprArithmPrecedence() {
    val p = prepareCode("int a = 1 + 2 * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign Operators.ADD.with {
      lhs = int(1)
      rhs = 2 to 3 with Operators.MUL
    }) assertEquals p.root.getDeclarations()[0]
  }
}
