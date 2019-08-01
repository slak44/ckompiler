package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

class ExpressionTests {
  @Test
  fun `Arithmetic Precedence`() {
    val p = prepareCode("int a = 1 + 2 * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level 1`() {
    val p = prepareCode("int a = (1 + 2) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add 2) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level 2`() {
    val p = prepareCode("int a = (1 + (2 + 3)) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add 3)) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level Lots`() {
    val p = prepareCode("int a = (1 + (2 + (3 + (4 + (5 + (6)))))) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add (3 add (4 add (5 add 6))))) mul 3)) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Unmatched Parens`() {
    val p = prepareCode("int a = (1 * (2 + 3);", source)
    assertEquals(listOf(
        DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET), p.diags.ids)
    int declare ("a" assign ErrorExpression()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Unmatched Right Paren At End Of Expr`() {
    val p = prepareCode("int a = 1 + 1);", source)
    assertEquals(listOf(
        DiagnosticId.EXPECTED_SEMI_AFTER,
        DiagnosticId.EXPECTED_EXTERNAL_DECL), p.diags.ids)
    int declare ("a" assign (1 add 1)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unexpected Bracket In Expression`() {
    val p = prepareCode("""
      int main() {
        1 + 1 +
      }
    """.trimIndent(), source)
    assert(p.diags.ids.contains(DiagnosticId.EXPECTED_SEMI_AFTER))
    int func "main" body compoundOf(
        1 add 1 add ErrorExpression()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Size Of Primary Expression`() {
    val p = prepareCode("int a = sizeof 1;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign sizeOf(1)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Simple Prefix Increment`() {
    val p = prepareCode("int a = ++b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign PrefixIncrement(nameRef("b", ErrorType))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Paren Prefix Increment`() {
    // This is invalid code, but valid grammar
    val p = prepareCode("int a = ++(1);", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PrefixIncrement(int(1))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Simple Postfix Increment`() {
    val p = prepareCode("int a = b++;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign PostfixIncrement(nameRef("b", ErrorType))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Paren Postfix Increment`() {
    // This is invalid code, but valid grammar
    val p = prepareCode("int a = (1)++;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PostfixIncrement(int(1))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unary Reference`() {
    val p = prepareCode("int a = &b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign UnaryOperators.REF[nameRef("b", ErrorType)]) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Unary Expect Primary`() {
    val p = prepareCode("int a = &;", source)
    p.assertDiags(DiagnosticId.EXPECTED_PRIMARY)
  }

  @Test
  fun `Unary Expect Expression At End Of Input`() {
    val p = prepareCode("int a = &", source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR, DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun `Unary With Lots Of Operators And Ignore Types`() {
    val p = prepareCode("int a = *&+-~!b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign
        UnaryOperators.DEREF[UnaryOperators.REF[UnaryOperators.PLUS[UnaryOperators.MINUS[
            UnaryOperators.BIT_NOT[UnaryOperators.NOT[nameRef("b", ErrorType)]]]]]]
        ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Comma Operator`() {
    val p = prepareCode("""
      int main() {
        123 + 124, 35 + 36;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    (123 add 124) comma (35 add 36) assertEquals p.root.decls[0].fn.block.items[0].st
  }

  @Test
  fun `Comma Operator Has Correct Associativity`() {
    val p = prepareCode("""
      int main() {
        123 + 124, 35 + 36, 1 + 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    ((123 add 124) comma (35 add 36)) comma (1 add 2) assertEquals
        p.root.decls[0].fn.block.items[0].st
  }

  @Test
  fun `Sizeof Type Name`() {
    val p = prepareCode("int a = sizeof(int);", source)
    p.assertNoDiagnostics()
    int declare ("a" assign SizeofTypeName(SignedIntType)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Parenthesized Expression`() {
    val p = prepareCode("int a = sizeof(1 + 2 * 3);", source)
    p.assertNoDiagnostics()
    int declare ("a" assign sizeOf(1 add (2 mul 3))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Bad Parens`() {
    val p = prepareCode("int a = sizeof(int;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
  }

  @Test
  fun `Sizeof Bad Parens Expr`() {
    val p = prepareCode("int a = sizeof(1;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
  }

  @Test
  fun `Subscript Unmatched Parens`() {
    val p = prepareCode("int main() {int a[12]; a[2;}", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
  }

  @Test
  fun `Subscript Empty`() {
    val p = prepareCode("int main() {int a[12]; a[];}", source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
  }

  @ParameterizedTest
  @ValueSource(strings = ["main[1]", "1[main]", "main[main]", "(*&main)[1]"])
  fun `Subscripts Of Function Must Fail`(subscriptToTest: String) {
    val p = prepareCode("int main() {$subscriptToTest;}", source)
    p.assertDiags(DiagnosticId.SUBSCRIPT_OF_FUNCTION)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "v[1]", "v[-12]", "v['a']", "v[0]", "v[12L]", "v[1U]", "v[23ULL]", "v[35LL]"])
  fun `Valid Subscripts`(subscriptToTest: String) {
    val p = prepareCode("int main() {int v[123];$subscriptToTest;}", source)
    p.assertNoDiagnostics()
    assert(p.root.decls[0].fn.block.items[1].st is ArraySubscript)
  }

  @ParameterizedTest
  @ValueSource(strings = ["v[v]", "v[main]", "v[\"sdfghsr\"]", "v[&main]"])
  fun `Subscripts That Are Not Integers`(subscriptToTest: String) {
    val p = prepareCode("int main() {int v[123];$subscriptToTest;}", source)
    p.assertDiags(DiagnosticId.SUBSCRIPT_NOT_INTEGRAL)
  }

  @ParameterizedTest
  @ValueSource(strings = ["123[123]", "1.2[123]", "123[1.2]", "'a'[123]"])
  fun `Not Subscriptable`(subscriptToTest: String) {
    val p = prepareCode("int main() {$subscriptToTest;}", source)
    p.assertDiags(DiagnosticId.INVALID_SUBSCRIPTED)
  }
}
