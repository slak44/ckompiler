package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.DoubleType
import slak.ckompiler.parser.FunctionType
import slak.ckompiler.parser.SignedIntType
import slak.test.*

class FunctionCallTests {
  @Test
  fun `No Arg Call`() {
    val p = prepareCode("""
      int f();
      int a = f();
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, listOf()))
    int declare ("a" assign f()) assertEquals p.root.decls[1]
  }

  @Test
  fun `One Arg Call`() {
    val p = prepareCode("""
      int f(int x);
      int a = f(123);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, listOf(SignedIntType)))
    int declare ("a" assign f(123)) assertEquals p.root.decls[1]
  }

  @Test
  fun `One Expr Arg Call`() {
    val p = prepareCode("""
      int f(int x);
      int a = f(123 - 123);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, listOf(SignedIntType)))
    int declare ("a" assign f(123 sub 123)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Two Arg Call`() {
    val p = prepareCode("""
      int f(int x, double y);
      int a = f(123, 5.5);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, listOf(SignedIntType, DoubleType)))
    int declare ("a" assign f(123, 5.5)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Two Expr Arg Call`() {
    val p = prepareCode("""
      int f(int x, double y);
      int a = f(123 - 123, 5.1*2.3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, listOf(SignedIntType, DoubleType)))
    int declare ("a" assign f(123 sub 123, 5.1 mul 2.3)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Three Arg Call`() {
    val p = prepareCode("""
      int f(int a, int b, int c);
      int a = f(1,2,3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, List(3) { SignedIntType }))
    int declare ("a" assign f(1, 2, 3)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Paren Expr Arg Call`() {
    val p = prepareCode("""
      int f(int a, int b, int c);
      int a = f(1,(2+2)*4,3);
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, List(3) { SignedIntType }))
    int declare ("a" assign f(1, (2 add 2) mul 4, 3)) assertEquals p.root.decls[1]
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "int a = 1();",
    "int b = (2+2)();",
    "int st = 45; int c = st();",
    """
      struct some_type {int x, y;};
      struct some_type value;
      int d = value();
    """
  ])
  fun `Can't Call Object Type`(functionCallStr: String) {
    val p = prepareCode(functionCallStr, source)
    p.assertDiags(DiagnosticId.CALL_OBJECT_TYPE)
  }

  @Test
  fun `No Misleading Error For ErrorType`() {
    val p = prepareCode("""
      int b = a();
    """.trimIndent(), source)
    assert(DiagnosticId.CALL_OBJECT_TYPE !in p.diags.ids)
  }
}
