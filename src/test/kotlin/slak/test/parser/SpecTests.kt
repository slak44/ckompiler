package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.Declaration
import slak.ckompiler.parser.StructNameSpecifier
import slak.ckompiler.parser.VoidType
import slak.test.*
import kotlin.test.assertEquals

class SpecTests {
  @Test
  fun basicTests() {
    val p = prepareCode("""
      int a = 1;
      long long b = 2;
      long unsigned long c = 3;
      double long d = 4;
      char signed e = 5;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign int(1)) assertEquals p.root.decls[0]
    longLong declare ("b" assign int(2)) assertEquals p.root.decls[1]
    uLongLong declare ("c" assign int(3)) assertEquals p.root.decls[2]
    longDouble declare ("d" assign int(4)) assertEquals p.root.decls[3]
    signedChar declare ("e" assign int(5)) assertEquals p.root.decls[4]
  }

  @Test
  fun incompatibleIntInt() {
    // Clang doesn't warn on this, it errors, so we copy them
    val p = prepareCode("int int a = 1;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun incompatibleMultipleStorageClass() {
    val p = prepareCode("static extern auto int a = 1;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC, DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun threadLocalCompat() {
    val p = prepareCode("""
      static _Thread_local int a = 1;
      extern _Thread_local int b = 1;
      _Thread_local static int c = 1;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun threadLocalIncompat() {
    val p = prepareCode("""
      _Thread_local auto int a = 1;
      register _Thread_local int b = 1;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC, DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun missingTypeSpecExternal() {
    val p = prepareCode("a = 1;", source)
    p.assertDiags(DiagnosticId.EXPECTED_EXTERNAL_DECL)
  }

  @Test
  fun missingTypeSpecWithConstExternal() {
    val p = prepareCode("const a = 1;", source)
    p.assertDiags(DiagnosticId.MISSING_TYPE_SPEC)
  }

  @Test
  fun missingTypeSpec() {
    val p = prepareCode("int main() { const a = 1; }", source)
    p.assertDiags(DiagnosticId.MISSING_TYPE_SPEC)
  }

  @Test
  fun duplicateUnsigned() {
    val p = prepareCode("int main() { const unsigned unsigned a = 1; }", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun duplicateStorageClassSpecs() {
    val p = prepareCode("int main() { register register int a = 1; }", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun duplicateTypeQualifiers() {
    val p = prepareCode("int main() { const const int a = 1; }", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun duplicateFunSpecs() {
    val p = prepareCode("inline inline int main() {}", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun typeNotSigned() {
    val p = prepareCode("int main() { signed _Bool a = 1; }", source)
    p.assertDiags(DiagnosticId.TYPE_NOT_SIGNED)
  }

  @Test
  fun typeNotSignedRev() {
    val p = prepareCode("int main() { _Bool signed a = 1; }", source)
    p.assertDiags(DiagnosticId.TYPE_NOT_SIGNED)
  }

  @Test
  fun voidFunc() {
    val p = prepareCode("void f();", source)
    p.assertNoDiagnostics()
    assert((p.root.decls[0] as Declaration).declSpecs.typeSpec is VoidType)
  }

  @Test
  fun inlineNoreturnAllowed() {
    val p = prepareCode("""
      inline _Noreturn void f() {}
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun structDecl() {
    val p = prepareCode("struct x a = 1;", source)
    p.assertNoDiagnostics()
    assertEquals(StructNameSpecifier(name("x")),
        (p.root.decls[0] as Declaration).declSpecs.typeSpec)
  }

  @Test
  fun structMustDefine() {
    val p = prepareCode("auto struct const;", source)
    assert(p.diags.ids.contains(DiagnosticId.ANON_STRUCT_MUST_DEFINE))
  }

  @Test
  fun structSimple() {
    val p = prepareCode("struct vec2 {int x, y;};", source)
    p.assertNoDiagnostics()
    val structDef = struct("vec2", listOf(
        int declare listOf("x", "y")
    ))
    assertEquals(structDef, (p.root.decls[0] as Declaration).declSpecs.typeSpec)
  }

  @Test
  fun structNoSemiAfter() {
    val p = prepareCode("""
      struct vec2 {int x, y;}
      int main() {}
    """.trimIndent(), source)
    // It is not easy to determine that it's just a missing semicolon. Interpreting this code as an
    // incompatible type spec is correct, but produces a worse error message.
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun structNoSemiAfterWhenEndOfFile() {
    val p = prepareCode("struct vec2 {int x, y;}", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun structNoSemiInStructDecl() {
    val p = prepareCode("struct vec2 {int x, y};", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun structDefinitionWithDeclAfter() {
    val p = prepareCode("struct vec2 {int x, y;} v1, v2, v3;", source)
    p.assertNoDiagnostics()
    val vec2 = struct("vec2", listOf(
        int declare listOf("x", "y")
    )).toSpec()
    vec2 declare listOf("v1", "v2", "v3") assertEquals p.root.decls[0]
  }

  @Test
  fun structInStruct() {
    val p = prepareCode("""
      struct outer {
        struct inner {
          int x, y;
        } in1, in2;
      } out;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    struct("outer", listOf(
        struct("inner", listOf(
            int declare listOf("x", "y")
        )).toSpec() declare listOf("in1", "in2")
    )).toSpec() declare "out" assertEquals p.root.decls[0]
  }

  @Test
  fun structBits() {
    val p = prepareCode("struct vec2 {int x : 16, y : 20;};", source)
    p.assertNoDiagnostics()
    val structDef = struct("vec2", listOf(
        int declare listOf("x" bitSize 16, "y" bitSize 20)
    ))
    assertEquals(structDef, (p.root.decls[0] as Declaration).declSpecs.typeSpec)
  }

  @Test
  fun structIncompatAfter() {
    val p = prepareCode("int struct vec2 {int x, y;} thing;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun structIncompatBefore() {
    val p = prepareCode("struct vec2 {int x, y;} int thing;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun structJustNameIncompat() {
    val p = prepareCode("struct vec2 int thing;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun structAnon() {
    val p = prepareCode("struct {int x, y;} pos;", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun structNoStorageQual() {
    val p = prepareCode("""
      struct {register int a;} struct1;
      struct {_Thread_local int a;} struct2;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.SPEC_NOT_ALLOWED, DiagnosticId.SPEC_NOT_ALLOWED)
  }

  @Test
  fun structDoesntDeclareAnything() {
    val p = prepareCode("""
      struct {double a, b; int;} struct1;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.MISSING_DECLARATIONS)
  }
}
