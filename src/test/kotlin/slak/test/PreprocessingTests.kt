package slak.test

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Identifier
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.lexer.Punctuators
import kotlin.test.assertEquals

class PreprocessingTests {
  @Test
  fun `Comment Single Line`() {
    val l = preparePP("""
      // lalalalla int = dgdgd 1 .34/ // /////
      int a = 1;
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, 1, Punctuators.SEMICOLON)
  }

  @Test
  fun `Comment Multi-line`() {
    val l = preparePP("""
      /* lalalalla int = dgdgd 1 .34/ // ////* /*
      asf
      fg` ȀȁȂȃȄȅȆȇȈȉ ȊȋȌȍȎȏ02 10ȐȑȒȓȔȕȖȗ ȘșȚțȜȝȞ
      32ng
      g */
      int a = 1;
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, 1, Punctuators.SEMICOLON)
  }

  @Test
  fun `Comment Multi-line Empty`() {
    val l = preparePP("/**/", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Comment Multi-line Unfinished`() {
    assertPPDiagnostic("""
      /* lalalalla int = dgdgd 1 .34/ // ////* /*
      asf
      fg` ȀȁȂȃȄȅȆȇȈȉ ȊȋȌȍȎȏ02 10ȐȑȒȓȔȕȖȗ ȘșȚțȜȝȞ
      32ng
      g
      int a = 1;
    """.trimIndent(), source, DiagnosticId.UNFINISHED_COMMENT)
  }

  @Test
  fun `Comment At End Of File`() {
    val l = preparePP("//", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Comments In Strings`() {
    val l = preparePP("\"//\" \"/* */\"", source)
    l.assertNoDiagnostics()
    l.assertTokens("//", "/* */")
  }

  @Test
  fun `Multi-line Comment End In Regular Comment`() {
    val l = preparePP("// */", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Header Name Recognized`() {
    val test1 = preparePP(resource("headers/system/test.h").readText(), source)
    val test2 = preparePP(resource("headers/users/test.h").readText(), source)
    val l = preparePP("#include <test.h>\n#include \"test.h\"", source)
    test1.assertNoDiagnostics()
    test2.assertNoDiagnostics()
    l.assertNoDiagnostics()
    assertEquals(test1.tokens + test2.tokens, l.tokens)
  }

  @Test
  fun `Header Name Is Only Recognized In Include And Pragma Directives`() {
    val l = preparePP("#define <test.h>", source)
    l.assertDiags(DiagnosticId.MACRO_NAME_NOT_IDENT)
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Include Directive Extra Tokens`() {
    val l = preparePP("#include <test.h> BLABLABLA", source)
    l.assertDiags(DiagnosticId.EXTRA_TOKENS_DIRECTIVE)
    val test1 = preparePP(resource("headers/system/test.h").readText(), source)
    test1.assertNoDiagnostics()
    assertEquals(l.tokens, test1.tokens)
  }

  @Disabled("We don't implement #including macro'd things yet, and the PP takes that path here")
  @Test
  fun `Header Name Unfinished Sequence`() {
    assertPPDiagnostic("#include <test.h", source, DiagnosticId.EXPECTED_H_Q_CHAR_SEQUENCE)
    assertPPDiagnostic("#include \"test.h", source, DiagnosticId.EXPECTED_H_Q_CHAR_SEQUENCE)
    assertPPDiagnostic("#include <test.h\n>", source, DiagnosticId.EXPECTED_H_Q_CHAR_SEQUENCE)
  }

  @Test
  fun `Trigraphs Recognized`() {
    assertPPDiagnostic("??< ??>", source,
        DiagnosticId.TRIGRAPH_PROCESSED, DiagnosticId.TRIGRAPH_PROCESSED)
  }

  @Test
  fun `Line Splicing`() {
    val l = preparePP("tab\\\nle", source)
    l.assertNoDiagnostics()
    assertEquals(listOf(Identifier("table")), l.tokens)
  }

  @Disabled("FIXME: deal with the diagnostics in Preprocessors and this will pass")
  @Test
  fun `Error Directive`() {
    assertPPDiagnostic("#error", source, DiagnosticId.PP_ERROR_DIRECTIVE)
    assertPPDiagnostic("#error\n", source, DiagnosticId.PP_ERROR_DIRECTIVE)
    assertPPDiagnostic("#error This is an error.\n", source, DiagnosticId.PP_ERROR_DIRECTIVE)
    assertPPDiagnostic("#error This is an error.->23&&\n", source, DiagnosticId.PP_ERROR_DIRECTIVE)
    assertPPDiagnostic("#error 123sdadg\n", source, DiagnosticId.PP_ERROR_DIRECTIVE)
  }

  @Test
  fun `Define Directive Errors`() {
    assertPPDiagnostic("#define", source, DiagnosticId.MACRO_NAME_MISSING)
    assertPPDiagnostic("#define ;", source, DiagnosticId.MACRO_NAME_NOT_IDENT)
    assertPPDiagnostic("#define ()", source, DiagnosticId.MACRO_NAME_NOT_IDENT)
    assertPPDiagnostic("#define ASD\n#define ASD 123", source,
        DiagnosticId.MACRO_REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
    assertPPDiagnostic("#define ASD\n#define ASD", source)
    assertPPDiagnostic("#define ASD 123\n#define ASD 123", source)
  }

  @Test
  fun `Define Directive Eats Line After Error`() {
    val l = preparePP("#define ; asdf 123", source)
    l.assertDiags(DiagnosticId.MACRO_NAME_NOT_IDENT)
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Define Directive With No Replacement List Parsing`() {
    val l = preparePP("#define FOO bar", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

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
    """.trimIndent(), source,
        DiagnosticId.EXTRA_TOKENS_DIRECTIVE, DiagnosticId.MACRO_NAME_NOT_IDENT)
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
  @ValueSource(strings = ["#if 0", "#ifdef TEST", "#ifndef TEST"])
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
      #ifdef $code
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
    assertPPDiagnostic("""
      #if defined defined
      #endif
    """.trimIndent(), source, DiagnosticId.NOT_DEFINED_IS_0)
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
    assertPPDiagnostic("#if defined", source, DiagnosticId.EXPECTED_IDENT)
  }
}
