package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
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
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int declare ("a" assign int(1)) assertEquals p.root.decls[0]
    longLong declare ("b" assign int(2)) assertEquals p.root.decls[1]
    uLongLong declare ("c" assign int(3)) assertEquals p.root.decls[2]
    longDouble declare ("d" assign int(4)) assertEquals p.root.decls[3]
  }

  @Test
  fun incompatibleIntInt() {
    // Clang doesn't warn on this, it errors, so we copy them
    val p = prepareCode("int int a = 1;", source)
    assertEquals(listOf(DiagnosticId.INCOMPATIBLE_DECL_SPEC), p.diags.ids)
  }

  @Test
  fun missingTypeSpecExternal() {
    val p = prepareCode("a = 1;", source)
    assertEquals(listOf(DiagnosticId.EXPECTED_EXTERNAL_DECL), p.diags.ids)
  }

  @Test
  fun missingTypeSpecWithConstExternal() {
    val p = prepareCode("const a = 1;", source)
    assertEquals(listOf(DiagnosticId.MISSING_TYPE_SPEC), p.diags.ids)
  }

  @Test
  fun missingTypeSpec() {
    val p = prepareCode("int main() { const a = 1; }", source)
    assertEquals(listOf(DiagnosticId.MISSING_TYPE_SPEC), p.diags.ids)
  }
}
