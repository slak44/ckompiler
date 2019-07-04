package slak.test

import org.junit.Ignore
import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
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

  @Ignore("We don't implement #including macro'd things yet, and the PP takes that path here")
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

  @Ignore("FIXME: deal with the diagnostics in Preprocessors and this will pass")
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
}
