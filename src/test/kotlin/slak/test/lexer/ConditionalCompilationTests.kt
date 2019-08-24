package slak.test.lexer

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Identifier
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.lexer.Punctuators
import slak.test.*
import slak.test.assertNoDiagnostics
import slak.test.assertPPDiagnostic
import slak.test.preparePP
import slak.test.source

class ConditionalCompilationTests {
  @Test
  fun `IfSection Extra Tokens`() {
    assertPPDiagnostic("""
      #ifdef TEST 123 foo bar baz
      #endif
    """.trimIndent(), source, DiagnosticId.EXTRA_TOKENS_DIRECTIVE)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "123",
    ".",
    ";",
    "*",
    "#",
    "<",
    "\"\"",
    "0xDEAD",
    "(int) 1"
  ])
  fun `Ifdef Not Identifiers`(thing: String) {
    assertPPDiagnostic("""
      #ifdef $thing
      #endif
    """.trimIndent(), source, DiagnosticId.MACRO_NAME_NOT_IDENT)
    assertPPDiagnostic("""
      #ifndef $thing
      #endif
    """.trimIndent(), source, DiagnosticId.MACRO_NAME_NOT_IDENT)
  }

  @Test
  fun `IfSection Not Ident And Extra Tokens`() {
    assertPPDiagnostic("""
      #ifdef 123 foo bar baz
      #endif
    """.trimIndent(), source, DiagnosticId.MACRO_NAME_NOT_IDENT)
  }

  @Test
  fun `IfSection Macro Name Missing`() {
    assertPPDiagnostic("""
      #ifdef
      #endif
    """.trimIndent(), source, DiagnosticId.MACRO_NAME_MISSING)
  }

  @ParameterizedTest
  @ValueSource(strings = ["#endif", "#else", "#elif"])
  fun `Directives Outside IfSection`(code: String) {
    assertPPDiagnostic(code + '\n', source, DiagnosticId.DIRECTIVE_WITHOUT_IF)
  }

  @ParameterizedTest
  @ValueSource(strings = ["#endif", "#else", "#elif"])
  fun `Directives Outside IfSection With Extra Tokens`(code: String) {
    assertPPDiagnostic("$code BLABLA\n", source,
        DiagnosticId.DIRECTIVE_WITHOUT_IF, DiagnosticId.EXTRA_TOKENS_DIRECTIVE)
  }

  @ParameterizedTest
  @ValueSource(strings = ["#if 0", "#ifdef TEST", "#ifndef TEST"])
  fun `IfSection Empty`(code: String) {
    val l = preparePP("""
      $code
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Empty Directive At End Of Source`() {
    val l = preparePP("#", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Empty Directive At End Of Source Inside IfSection`() {
    val l = preparePP("#if 1\n#", source)
    l.assertDiags(DiagnosticId.UNTERMINATED_CONDITIONAL)
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Empty Directive At End Of IfSection`() {
    val l = preparePP("""
      #if 0
      #
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @ParameterizedTest
  @ValueSource(strings = ["#if 0", "#ifdef TEST", "#ifndef TEST"])
  fun `Unterminated IfSection`(code: String) {
    assertPPDiagnostic(code, source, DiagnosticId.UNTERMINATED_CONDITIONAL)
  }

  @ParameterizedTest
  @ValueSource(strings = ["#if 0", "#if 1", "#ifdef TEST", "#ifndef TEST"])
  fun `Nested IfSection`(code: String) {
    val l = preparePP("""
      $code
        $code
        #endif
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @ParameterizedTest
  @ValueSource(strings = ["#else", "#elif 123"])
  fun `Else Not Last Thing`(code: String) {
    assertPPDiagnostic("""
      #if 0
      #else
      $code
      #endif
    """.trimIndent(), source, DiagnosticId.ELSE_NOT_LAST)
  }

  @ParameterizedTest
  @ValueSource(strings = ["#if 0", "#ifdef TEST", "#ifndef TEST"])
  fun `IfSection With Else`(code: String) {
    val l = preparePP("""
      $code
      #else
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @ParameterizedTest
  @ValueSource(strings = ["#if 0", "#ifdef TEST", "#ifndef TEST"])
  fun `IfSection With ElIf And Else`(code: String) {
    val l = preparePP("""
      $code
      #elif 1
      #else
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `IfSection Inner Directives Extra Tokens`() {
    assertPPDiagnostic("""
      #ifdef TEST
      #elif 1 12345
      #else asdhg 1.1
      #endif bleh 34
    """.trimIndent(), source, *Array(3) { DiagnosticId.EXTRA_TOKENS_DIRECTIVE })
  }

  @Test
  fun `IfSection Code Is Added`() {
    val l = preparePP("""
      #if 1
      int a;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.SEMICOLON)
  }

  @Test
  fun `IfSection Define Is Added`() {
    val l = preparePP("""
      #if 1
      #define TEST 123
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
    assert(Identifier("TEST") in l.defines.keys)
  }

  @Test
  fun `IfSection Code Is Not Added`() {
    val l = preparePP("""
      #if 0
      int a;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `IfSection Define Is Not Added`() {
    val l = preparePP("""
      #if 0
      #define TEST 123
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
    assert(Identifier("TEST") !in l.defines.keys)
  }

  @Test
  fun `IfSection Else Branch Taken`() {
    val l = preparePP("""
      #if 0
      int a;
      #else
      int b;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("b"), Punctuators.SEMICOLON)
  }

  @Test
  fun `IfSection Else Branch Not Taken`() {
    val l = preparePP("""
      #if 1
      int a;
      #else
      int b;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.SEMICOLON)
  }

  @Test
  fun `IfSection ElIf Taken`() {
    val l = preparePP("""
      #if 0
      int a;
      #elif 1
      int b;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("b"), Punctuators.SEMICOLON)
  }

  @Test
  fun `IfSection ElIf Not Taken`() {
    val l = preparePP("""
      #if 0
      int a;
      #elif 0
      int b;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `IfSection ElIf With Else`() {
    val l = preparePP("""
      #if 0
      int a;
      #elif 0
      int b;
      #else
      int c;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("c"), Punctuators.SEMICOLON)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "1", "1 + 1", "11111", "1U", "2", "!0", "1 > 0", "1 <= 1", "22 == 22", "2 != 3", "(1) + 1",
    "!defined TEST", "(defined TEST) + 1"
  ])
  fun `IfSection Conditions True`(condition: String) {
    val l = preparePP("""
      #if $condition
      int a;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.SEMICOLON)
  }

  @ParameterizedTest
  @ValueSource(strings = ["0", "!1", "1 - 1", "0 > 1", "defined TEST"])
  fun `IfSection Conditions False`(condition: String) {
    val l = preparePP("""
      #if $condition
      int a;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `IfSection ElIf Missing Condition`() {
    assertPPDiagnostic("""
      #ifdef TEST
      #elif
      #endif
    """.trimIndent(), source, DiagnosticId.ELIF_NO_CONDITION)
  }

  @ParameterizedTest
  @ValueSource(strings = ["test", "int", "bla", "zero", "float", "_Alignas"])
  fun `IfSection Condition Identifiers Are Zero`(code: String) {
    assertPPDiagnostic("""
      #if $code
      #endif
    """.trimIndent(), source, DiagnosticId.NOT_DEFINED_IS_0)
  }

  @ParameterizedTest
  @EmptySource
  @ValueSource(strings = [
    "123", "2.2", ",", "!", "-1", "%", ")", "(", "[", "=", "#", "( 123 )", "( - )"
  ])
  fun `Defined Operator Expected Identifier`(code: String) {
    assertPPDiagnostic("""
      #if defined $code
      #endif
    """.trimIndent(), source, DiagnosticId.EXPECTED_IDENT)
  }

  @Test
  fun `Defined Operator On Defined Identifier`() {
    val l = preparePP("""
      #if defined defined
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Defined Operator Unmatched Paren`() {
    assertPPDiagnostic("""
      #if defined ( TEST
      #endif
    """.trimIndent(), source, DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
  }

  @Test
  fun `Defined Operator At End Of File`() {
    assertPPDiagnostic("#if defined", source,
        DiagnosticId.EXPECTED_IDENT, DiagnosticId.UNTERMINATED_CONDITIONAL)
  }
}
