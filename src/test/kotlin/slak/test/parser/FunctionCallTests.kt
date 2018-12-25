package slak.test.parser

import org.junit.Test
import slak.ckompiler.parser.FunctionCall
import slak.test.*

class FunctionCallTests {
  @Test
  fun noArgCall() {
    val p = prepareCode("""
      int f();
      int a = f();
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call emptyList()))
  }

  @Test
  fun oneArgCall() {
    val p = prepareCode("""
      int f(int x);
      int a = f(123);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(int(123))))
  }

  @Test
  fun oneExprArgCall() {
    val p = prepareCode("""
      int f(int x);
      int a = f(123 - 123);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(123 sub 123)))
  }

  @Test
  fun twoArgCall() {
    val p = prepareCode("""
      int f(int x, double y);
      int a = f(123, 5.5);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(int(123), double(5.5))))
  }

  @Test
  fun twoExprArgCall() {
    val p = prepareCode("""
      int f(int x, double y);
      int a = f(123 - 123, 5.1*2.3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(123 sub 123, 5.1 mul 2.3)))
  }

  @Test
  fun threeArgCall() {
    val p = prepareCode("""
      int f(int a, int b, int c);
      int a = f(1,2,3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(int(1), int(2), int(3))))
  }

  @Test
  fun parenExprArgCall() {
    val p = prepareCode("""
      int f(int a, int b, int c);
      int a = f(1,(2+2)*4,3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(int(1), (2 add 2) mul 4, int(3))))
  }

  @Test
  fun callAnExpr() {
    val p = prepareCode("int a = (a + 72)(123);", source)
    p.assertNoDiagnostics()
    int declare ("a" assign FunctionCall("a" add 72, listOf(int(123))))
  }
}
