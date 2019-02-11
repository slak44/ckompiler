package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.Operators
import slak.test.*

class FunctionCallTests {
  @Test
  fun `No Arg Call`() {
    val p = prepareCode("""
      int f();
      int a = f();
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call emptyList())) assertEquals p.root.decls[1]
  }

  @Test
  fun `One Arg Call`() {
    val p = prepareCode("""
      int f(int x);
      int a = f(123);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(123))) assertEquals p.root.decls[1]
  }

  @Test
  fun `One Expr Arg Call`() {
    val p = prepareCode("""
      int f(int x);
      int a = f(123 - 123);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(123 sub 123))) assertEquals p.root.decls[1]
  }

  @Test
  fun `Two Arg Call`() {
    val p = prepareCode("""
      int f(int x, double y);
      int a = f(123, 5.5);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(int(123), double(5.5)))) assertEquals p.root.decls[1]
  }

  @Test
  fun `Two Expr Arg Call`() {
    val p = prepareCode("""
      int f(int x, double y);
      int a = f(123 - 123, 5.1*2.3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(123 sub 123, 5.1 mul 2.3))) assertEquals
        p.root.decls[1]
  }

  @Test
  fun `Three Arg Call`() {
    val p = prepareCode("""
      int f(int a, int b, int c);
      int a = f(1,2,3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(1, 2, 3))) assertEquals p.root.decls[1]
  }

  @Test
  fun `Paren Expr Arg Call`() {
    val p = prepareCode("""
      int f(int a, int b, int c);
      int a = f(1,(2+2)*4,3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign ("f" call listOf(1, (2 add 2) mul 4, 3))) assertEquals p.root.decls[1]
  }

  @Test
  fun `Call An Expr`() {
    val p = prepareCode("""
      int f();
      int a = (&f)();
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign (Operators.REF apply "f" call emptyList())) assertEquals p.root.decls[1]
  }

  @Test
  fun `Can't Call Object Type`() {
    val p = prepareCode("""
      int a = 1();
      int b = (2+2)();
      int st = 45;
      int c = st();
      struct some_type {int x, y;};
      struct some_type value;
      int d = value();
    """.trimIndent(), source)
    p.assertDiags(*Array(4) { DiagnosticId.CALL_OBJECT_TYPE })
  }
}
