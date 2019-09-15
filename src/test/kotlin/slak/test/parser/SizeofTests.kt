package slak.test.parser

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*
import slak.test.source
import slak.test.withParams

class SizeofTests {
  @Test
  fun `Size Of Primary Expression`() {
    val p = prepareCode("int a = sizeof 1;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign SignedIntType.cast(sizeOf(1))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Int`() {
    val p = prepareCode("""
      int main() {
        sizeof ( int );
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        sizeOf(SignedIntType)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Parenthesized Expression`() {
    val p = prepareCode("int a = sizeof(1 + 2 * 3);", source)
    p.assertNoDiagnostics()
    int declare ("a" assign SignedIntType.cast(sizeOf(1 add (2 mul 3)))) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Sizeof Bad Parens`() {
    val p = prepareCode("int a = sizeof(int;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
  }

  @Test
  fun `Sizeof Bad Parens Expr`() {
    val p = prepareCode("int a = sizeof(1;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
  }

  @Test
  fun `Sizeof Int Missing Parens`() {
    val p = prepareCode("""
      int main() {
        sizeof int;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.SIZEOF_TYPENAME_PARENS)
    int func ("main" withParams emptyList()) body compoundOf(
        ErrorExpression()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Precedence`() {
    val p = prepareCode("""
      int main() {
        sizeof 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        sizeOf(1) add UnsignedIntType.cast(1)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Array Type Name`() {
    val p = prepareCode("""
      int main() {
        sizeof( int[2] );
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        sizeOf(ArrayType(SignedIntType, ConstantSize(IntegerConstantNode(2)), false))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Function Type Name`() {
    val p = prepareCode("""
      int main() {
        sizeof( int() );
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.SIZEOF_ON_FUNCTION)
    int func ("main" withParams emptyList()) body compoundOf(
        sizeOf(FunctionType(SignedIntType, emptyList()))
    ) assertEquals p.root.decls[0]
  }

  @Disabled("have to deal with incomplete structs in type system first")
  @Test
  fun `Sizeof Incomplete Type`() {
    val p = prepareCode("struct x; int a = sizeof(struct x);", source)
    p.assertDiags(DiagnosticId.SIZEOF_ON_INCOMPLETE)
  }

  @Test
  fun `Sizeof Function Expression`() {
    val p = prepareCode("""
      int main() {
        sizeof main;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.SIZEOF_ON_FUNCTION)
    val main = nameRef("main", FunctionType(SignedIntType, emptyList()))
    int func ("main" withParams emptyList()) body compoundOf(
        sizeOf(main)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Sizeof Array Type`() {
    val p = prepareCode("""
      int main() {
        int v[20];
        sizeof v;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val v =
        nameRef("v", ArrayType(SignedIntType, ConstantSize(int(20)), isStorageRegister = false))
    int func ("main" withParams emptyList()) body compoundOf(
        int declare nameDecl("v")[20],
        sizeOf(v)
    ) assertEquals p.root.decls[0]
  }
}
