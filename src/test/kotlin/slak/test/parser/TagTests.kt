package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.Declaration
import slak.ckompiler.parser.Enumerator
import slak.test.*
import kotlin.test.assertEquals

class TagTests {
  @Test
  fun `Struct Keyword With Arrow Afterwords In Function`() {
    val p = prepareCode("int main() {struct -> a;}", source)
    // This ridiculous syntax produces ridiculous diagnostics
    assert(p.diags.isNotEmpty())
  }

  @Test
  fun `Struct Declaration`() {
    val p = prepareCode("struct x a = {};", source)
    p.assertDiags(DiagnosticId.VARIABLE_TYPE_INCOMPLETE)
    assertEquals(struct("x").typeSpec, (p.root.decls[0] as Declaration).declSpecs.typeSpec)
  }

  @Test
  fun `Struct Must Define`() {
    val p = prepareCode("auto struct const;", source)
    assert(DiagnosticId.ANON_TAG_MUST_DEFINE in p.diags.ids)
  }

  @Test
  fun `Struct Simple`() {
    val p = prepareCode("struct vec2 {int x, y;} a;", source)
    p.assertNoDiagnostics()
    val structDef = struct("vec2",
        int declare listOf("x", "y")
    )
    assertEquals(structDef, (p.root.decls[0] as Declaration).declSpecs.typeSpec)
  }

  @Test
  fun `Struct No Semi After`() {
    val p = prepareCode("""
      struct vec2 {int x, y;}
      int main() {}
    """.trimIndent(), source)
    // It is not easy to determine that it's just a missing semicolon. Interpreting this code as an
    // incompatible type spec is correct, but produces a worse error message.
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun `Struct No Semi After, When End Of File `() {
    val p = prepareCode("struct vec2 {int x, y;}", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun `Struct No Semi In Struct Declaration`() {
    val p = prepareCode("struct vec2 {int x, y};", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun `Struct No Semi In Struct Declaration With More Stuff After`() {
    val p = prepareCode("struct vec2 {int x, y int z;};", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun `Struct Definition With Declarators After`() {
    val p = prepareCode("struct vec2 {int x, y;} v1, v2, v3;", source)
    p.assertNoDiagnostics()
    val vec2 = struct("vec2",
        int declare listOf("x", "y")
    ).toSpec()
    vec2 declare listOf("v1", "v2", "v3") assertEquals p.root.decls[0]
  }

  @Test
  fun `Struct In Struct`() {
    val p = prepareCode("""
      struct outer {
        struct inner {
          int x, y;
        } in1, in2;
      } out;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    struct("outer",
        struct("inner",
            int declare listOf("x", "y")
        ).toSpec() declare listOf("in1", "in2")
    ).toSpec() declare "out" assertEquals p.root.decls[0]
  }

  @Test
  fun `Struct Bits`() {
    val p = prepareCode("struct vec2 {int x : 16, y : 20;} a;", source)
    p.assertNoDiagnostics()
    val structDef = struct("vec2",
        int declare listOf("x" bitSize 16, "y" bitSize 20)
    )
    assertEquals(structDef, (p.root.decls[0] as Declaration).declSpecs.typeSpec)
  }

  @Test
  fun `Struct Incompatible Specifier After`() {
    val p = prepareCode("int struct vec2 {int x, y;} thing;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun `Struct Incompatible Specifier Before`() {
    val p = prepareCode("struct vec2 {int x, y;} int thing;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun `Struct Name Specifier Is Incompatible`() {
    val p = prepareCode("struct vec2 int thing;", source)
    assert(DiagnosticId.INCOMPATIBLE_DECL_SPEC in p.diags.ids)
  }

  @Test
  fun `Struct Anonymous`() {
    val p = prepareCode("struct {int x, y;} pos;", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Struct No Storage Class`() {
    val p = prepareCode("""
      struct {register int a;} struct1;
      struct {_Thread_local int a;} struct2;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.SPEC_NOT_ALLOWED, DiagnosticId.SPEC_NOT_ALLOWED)
  }

  @Test
  fun `Struct Doesnt Declare Anything`() {
    val p = prepareCode("""
      struct {double a, b; int;} struct1;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.MISSING_DECLARATIONS)
  }

  @Test
  fun `Enum Simple`() {
    val p = prepareCode("""
      enum testing {TEST1, TEST2} testValue;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val enum = enum("testing", "TEST1", "TEST2")
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum Dangling Comma Is Valid`() {
    val p = prepareCode("""
      enum testing {TEST1, TEST2, } testValue;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val enum = enum("testing", "TEST1", "TEST2")
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum Starting With Comma`() {
    val p = prepareCode("""
      enum testing {, TEST2 } testValue;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT)
    enum("testing", "TEST2").toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum Missing Enumerator Inside`() {
    val p = prepareCode("""
      enum testing {TEST1, , TEST2 } testValue;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT)
    val enum = enum("testing", "TEST1", "TEST2")
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum Anonymous`() {
    val p = prepareCode("""
      enum {TEST1, TEST2} testValue;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val enum = enum(null, "TEST1", "TEST2")
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum Just Values`() {
    val p = prepareCode("""
      enum {TEST1, TEST2};
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.TRANSLATION_UNIT_NEEDS_DECL)
  }

  @Test
  fun `Enum With Init Value`() {
    val p = prepareCode("""
      enum testing { TEST = 1234 } testValue;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val enum = enum("testing", "TEST" withEnumConst 1234)
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum With Multiple Inits`() {
    val p = prepareCode("""
      enum testing { TEST = 1234, ASDFG, FOO = 2222 } testValue;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val enum = enum("testing", "TEST" withEnumConst 1234, "ASDFG", "FOO" withEnumConst 2222)
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum Missing = In Init`() {
    val p = prepareCode("""
      enum testing { TEST 1234 } testValue;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_ENUM_INIT, DiagnosticId.ENUM_IS_EMPTY)
    val enum = enum("testing")
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @Test
  fun `Enum Missing Init Expr`() {
    val p = prepareCode("""
      enum testing { TEST = } testValue;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
    val enum = enum("testing", Enumerator(name("TEST"), null, int(0)))
    enum.toSpec() declare "testValue" assertEquals p.root.decls[0]
  }

  @ParameterizedTest
  @ValueSource(strings = ["enum bla {};", "enum {};", "enum bla {} name;"])
  fun `Enum Can't Be Empty`(enum: String) {
    val p = prepareCode(enum, source)
    p.assertDiags(DiagnosticId.ENUM_IS_EMPTY)
  }
}
