package slak.test.lexer

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
import slak.test.*
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
  fun `Comment Multi-line Code`() {
    val l = preparePP("""
      int main() {
        /*double p = 2.0;
        int n = 2;
        printf("%.4f\n", pow(p, 1.0 / n));
        */
        return 0;
      }
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val zeroOct = IntegralConstant("0", IntegralSuffix.NONE, Radix.OCTAL)
    l.assertTokens(Keywords.INT, Identifier("main"), Punctuators.LPAREN, Punctuators.RPAREN,
        Punctuators.LBRACKET, Keywords.RETURN, zeroOct, Punctuators.SEMICOLON, Punctuators.RBRACKET)
  }

  @Test
  fun `Comment At End Of File`() {
    val l = preparePP("//", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Multi-line Comment At End Of File`() {
    val l = preparePP("/* 123c */", source)
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

  @ParameterizedTest
  @ValueSource(strings = ["stddef.h", "stdint.h", "math.h", "stdio.h"])
  fun `Including Stdlib Headers Works`(headerName: String) {
    val l = preparePP("#include <$headerName>", source)
    l.assertNoDiagnostics()
  }

  @Test
  fun `Missing Header Errors`() {
    val l = preparePP("#include </not/a/real/path/lol/thisHeaderDoesNotExist.h>", source)
    l.assertDiags(DiagnosticId.FILE_NOT_FOUND)
    assert(l.tokens.isEmpty())
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

  @ParameterizedTest
  @EmptySource
  @ValueSource(strings = [
    "\n", "    \n", "\t \n", " This is an error.\n", " This is an error.->23&&\n", " 123sdadg\n"
  ])
  fun `Error Directive`(string: String) {
    assertPPDiagnostic("#error$string", source, DiagnosticId.PP_ERROR_DIRECTIVE)
  }

  @Test
  fun `Non Directive`() {
    val l = preparePP("#foobar", source)
    l.assertDiags(DiagnosticId.INVALID_PP_DIRECTIVE)
    assert(l.tokens.isEmpty())
  }

  @ParameterizedTest
  @EmptySource
  @ValueSource(strings = [
    "\n", "  \n", " fsdhsgasd", "#", "?", " 123"
  ])
  fun `Ignored Pragmas`(string: String) {
    assertPPDiagnostic("#pragma$string", source, DiagnosticId.PRAGMA_IGNORED)
  }
}
