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
  fun `Basic Tests`() {
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
  fun `Incompatible Int Int`() {
    // Clang doesn't warn on this, it errors, so we copy them
    val p = prepareCode("int int a = 1;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun `Incompatible Multiple Storage Classes`() {
    val p = prepareCode("static extern auto int a = 1;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC, DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun `Thread Local Compatibilities`() {
    val p = prepareCode("""
      static _Thread_local int a = 1;
      extern _Thread_local int b = 1;
      _Thread_local static int c = 1;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Thread Local Incompatibilities`() {
    val p = prepareCode("""
      int main() {
        _Thread_local auto int a = 1;
        register _Thread_local int b = 1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC, DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun `Missing Type Specifier External`() {
    val p = prepareCode("a = 1;", source)
    p.assertDiags(DiagnosticId.EXPECTED_EXTERNAL_DECL)
  }

  @Test
  fun `Missing Type Specifier With Const External`() {
    val p = prepareCode("const a = 1;", source)
    p.assertDiags(DiagnosticId.MISSING_TYPE_SPEC)
  }

  @Test
  fun `Missing Type Specifier`() {
    val p = prepareCode("int main() { const a = 1; }", source)
    p.assertDiags(DiagnosticId.MISSING_TYPE_SPEC)
  }

  @Test
  fun `Duplicate Unsigned`() {
    val p = prepareCode("int main() { const unsigned unsigned a = 1; }", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun `Duplicate Storage Class Specifiers`() {
    val p = prepareCode("int main() { register register int a = 1; }", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun `Duplicate Type Qualifiers`() {
    val p = prepareCode("int main() { const const int a = 1; }", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun `Duplicate Function Specifiers`() {
    val p = prepareCode("inline inline int main() {}", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
  }

  @Test
  fun `Type Not Signed`() {
    val p = prepareCode("int main() { signed _Bool a = 1; }", source)
    p.assertDiags(DiagnosticId.TYPE_NOT_SIGNED)
  }

  @Test
  fun `Type Not Signed Reverse Order`() {
    val p = prepareCode("int main() { _Bool signed a = 1; }", source)
    p.assertDiags(DiagnosticId.TYPE_NOT_SIGNED)
  }

  @Test
  fun `Void Function`() {
    val p = prepareCode("void f();", source)
    p.assertNoDiagnostics()
    assert((p.root.decls[0] as Declaration).declSpecs.typeSpec is VoidType)
  }

  @Test
  fun `Inline And Noreturn Allowed`() {
    val p = prepareCode("""
      inline _Noreturn void f() {}
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Struct Declaration`() {
    val p = prepareCode("struct x a = 1;", source)
    p.assertNoDiagnostics()
    assertEquals(StructNameSpecifier(name("x")),
        (p.root.decls[0] as Declaration).declSpecs.typeSpec)
  }

  @Test
  fun `Struct Must Define`() {
    val p = prepareCode("auto struct const;", source)
    assert(p.diags.ids.contains(DiagnosticId.ANON_STRUCT_MUST_DEFINE))
  }

  @Test
  fun `Struct Simple`() {
    val p = prepareCode("struct vec2 {int x, y;};", source)
    p.assertNoDiagnostics()
    val structDef = struct("vec2", listOf(
        int declare listOf("x", "y")
    ))
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
  fun `Struct Definition With Declarators After`() {
    val p = prepareCode("struct vec2 {int x, y;} v1, v2, v3;", source)
    p.assertNoDiagnostics()
    val vec2 = struct("vec2", listOf(
        int declare listOf("x", "y")
    )).toSpec()
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
    struct("outer", listOf(
        struct("inner", listOf(
            int declare listOf("x", "y")
        )).toSpec() declare listOf("in1", "in2")
    )).toSpec() declare "out" assertEquals p.root.decls[0]
  }

  @Test
  fun `Struct Bits`() {
    val p = prepareCode("struct vec2 {int x : 16, y : 20;};", source)
    p.assertNoDiagnostics()
    val structDef = struct("vec2", listOf(
        int declare listOf("x" bitSize 16, "y" bitSize 20)
    ))
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
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
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
  fun `Function Invalid Storage Class Specifiers`() {
    val p = prepareCode("""
      register int f(); // 2 errors here
      auto int g(); // 2 errors here
      _Thread_local int h();

      int main() {
        register int f1();
        auto int f2();
        _Thread_local int f3();
      }
    """.trimIndent(), source)
    p.assertDiags(*Array(8) { DiagnosticId.ILLEGAL_STORAGE_CLASS })
  }

  @Test
  fun `Invalid Storage Class Specifiers On File Scoped Variables`() {
    val p = prepareCode("""
      register int x;
      auto int y;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.ILLEGAL_STORAGE_CLASS, DiagnosticId.ILLEGAL_STORAGE_CLASS)
  }
}
