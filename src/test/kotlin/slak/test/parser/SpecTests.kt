package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.RealDeclaration
import slak.ckompiler.parser.VoidType
import slak.test.*

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
  fun duplicateSpecs() {
    val p = prepareCode("int main() { const unsigned unsigned a = 1; }", source)
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
    assert((p.root.decls[0] as RealDeclaration).declSpecs.typeSpec is VoidType)
  }

  @Test
  fun structDecl() {
    val p = prepareCode("struct x a = 1;", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun structMustDefine() {
    val p = prepareCode("auto struct const;", source)
    assert(p.diags.ids.contains(DiagnosticId.ANON_STRUCT_MUST_DEFINE))
  }
}
