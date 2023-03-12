package slak.test.lexer

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Identifier
import slak.ckompiler.lexer.Keyword
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.lexer.Punctuators
import slak.test.*
import kotlin.test.assertEquals

class MacroReplacementTests {
  @ParameterizedTest
  @ValueSource(strings = [";", " ;", " ()", " #"])
  fun `Define Directive Macro Name Is Not Identifier`(str: String) {
    val l = preparePP("#define$str", source)
    l.assertDiags(DiagnosticId.MACRO_NAME_NOT_IDENT)
    assert(l.tokens.isEmpty())
  }

  @ParameterizedTest
  @ValueSource(strings = ["FOOBAR", "FOOBAR 123", "FOOBAR (2 + 4)"])
  fun `Redefinition Is Allowed If Identical`(def: String) {
    val l = preparePP("#define $def\n#define $def", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Define Directive Missing Name`() {
    assertPPDiagnostic("#define", source, DiagnosticId.MACRO_NAME_MISSING)
  }


  @Test
  fun `Different Redefinition Not Allowed`() {
    assertPPDiagnostic("#define ASD\n#define ASD 123", source,
        DiagnosticId.MACRO_REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Define Directive Eats Line After Error`() {
    val l = preparePP("#define ; asdf 123", source)
    l.assertDiags(DiagnosticId.MACRO_NAME_NOT_IDENT)
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Literal LPAREN In Define Directive Is Valid`() {
    val l = preparePP("#define LEFT_PAREN_OBJECT_MACRO (", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Define Directive With No Replacement List Parsing`() {
    val l = preparePP("#define FOO bar", source)
    l.assertNoDiagnostics()
    assert(l.tokens.isEmpty())
  }

  @Test
  fun `Object Macro Is Replaced`() {
    val l = preparePP("""
      #define TEST 123
      int a = TEST;
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertDefine("TEST", 123)
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, 123, Punctuators.SEMICOLON)
  }

  @Test
  fun `Object Macro Is Replaced With Multiple Tokens`() {
    val l = preparePP("""
      #define TEST 123 + 123
      int a = TEST;
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertDefine("TEST", 123, Punctuators.PLUS, 123)
    l.assertTokens(
        Keywords.INT, Identifier("a"), Punctuators.ASSIGN,
        123, Punctuators.PLUS, 123, Punctuators.SEMICOLON
    )
  }

  @Test
  fun `Object Macro Is Replaced In IfSection Condition`() {
    val l = preparePP("""
      #define TEST 1
      #if TEST
      int a = 123;
      #endif
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertDefine("TEST", 1)
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, 123, Punctuators.SEMICOLON)
  }

  @Test
  fun `Object Macro Is Replaced In Included File`() {
    val l = preparePP("""
      #define TEST 123
      #include "usesAMacro.h"
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertDefine("TEST", 123)
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, 123, Punctuators.SEMICOLON)
  }

  @Test
  fun `Object Macro From Included File Is Replaced`() {
    val l = preparePP("""
      #include "definesAMacro.h"
      int a = FOOBAR;
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertDefine("FOOBAR", 123)
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, 123, Punctuators.SEMICOLON)
  }

  @ParameterizedTest
  @ValueSource(strings = ["<test.h", "\"test.h", "<test.h\n>"])
  fun `Header Name Unfinished Sequence`(h: String) {
    assertPPDiagnostic("#include $h", source, DiagnosticId.EXPECTED_H_Q_CHAR_SEQUENCE)
  }

  @ParameterizedTest
  @ValueSource(strings = ["bla", ";", "#", "test.h>", "1 \"\""])
  fun `Include Expected Header Name`(h: String) {
    assertPPDiagnostic("#include $h", source, DiagnosticId.EXPECTED_HEADER_NAME)
  }

  @Test
  fun `Macro Replaced Include`() {
    val l = preparePP("""
      #define FOO <test
      #include FOO.h>
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertDefine("FOO", Punctuators.LT, Identifier("test"))
    val test1 = preparePP(resource("headers/system/test.h").readText(), source)
    test1.assertNoDiagnostics()
    assertEquals(l.tokens, test1.tokens)
  }

  @Test
  fun `Macro Replaced Include Extra Tokens`() {
    val l = preparePP("""
      #define FOO <test
      #include FOO.h> 123
    """.trimIndent(), source)
    l.assertDiags(DiagnosticId.EXTRA_TOKENS_DIRECTIVE)
    l.assertDefine("FOO", Punctuators.LT, Identifier("test"))
    val test1 = preparePP(resource("headers/system/test.h").readText(), source)
    test1.assertNoDiagnostics()
    assertEquals(l.tokens, test1.tokens)
  }

  @Test
  fun `Macro Replace Twice Works`() {
    val l = preparePP("""
      #define thing int
      int main() {
        thing a = 1;
        thing b = 2;
        return a + b;
      }
    """.trimIndent(), source)
    l.assertDefine("thing", Identifier("int"))
    assertEquals(3, l.tokens.filter { it is Keyword && it.value == Keywords.INT }.size)
  }
}
