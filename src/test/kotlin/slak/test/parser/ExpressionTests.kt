package slak.test.parser

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertNotNull

class ExpressionTests {
  @Test
  fun `Arithmetic Precedence`() {
    val p = prepareCode("int a = 1 + 2 * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level 1`() {
    val p = prepareCode("int a = (1 + 2) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add 2) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level 2`() {
    val p = prepareCode("int a = (1 + (2 + 3)) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add 3)) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level Lots`() {
    val p = prepareCode("int a = (1 + (2 + (3 + (4 + (5 + (6)))))) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add (3 add (4 add (5 add 6))))) mul 3)) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Unmatched Parens`() {
    val p = prepareCode("int a = (1 * (2 + 3);", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int declare ("a" assign ErrorExpression()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Unmatched Right Paren At End Of Expr`() {
    val p = prepareCode("int a = 1 + 1);", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER, DiagnosticId.EXPECTED_EXTERNAL_DECL)
    int declare ("a" assign (1 add 1)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unexpected Bracket In Expression`() {
    val p = prepareCode("""
      int main() {
        1 + 1 +
      }
    """.trimIndent(), source)
    assert(DiagnosticId.EXPECTED_SEMI_AFTER in p.diags.ids)
    int func "main" body compoundOf(
        1 add 1 add ErrorExpression()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Simple Prefix Increment`() {
    val p = prepareCode("int a = ++b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign prefixInc(nameRef("b", ErrorType))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Paren Prefix Increment`() {
    val p = prepareCode("int a = ++(b);", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign prefixInc(nameRef("b", ErrorType))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Simple Postfix Increment`() {
    val p = prepareCode("int a = b++;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign postfixInc(nameRef("b", ErrorType))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Paren Postfix Increment`() {
    val p = prepareCode("int a = (b)++;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign postfixInc(nameRef("b", ErrorType))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unary Reference`() {
    val p = prepareCode("int a = &b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign UnaryOperators.REF[nameRef("b", ErrorType)]) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Unary Expect Primary`() {
    val p = prepareCode("int a = &;", source)
    p.assertDiags(DiagnosticId.EXPECTED_PRIMARY)
  }

  @Test
  fun `Unary Expect Expression At End Of Input`() {
    val p = prepareCode("int a = &", source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR, DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun `Unary With Lots Of Operators And Ignore Types`() {
    val p = prepareCode("int a = *&+-~!b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign
        UnaryOperators.DEREF[UnaryOperators.REF[UnaryOperators.PLUS[UnaryOperators.MINUS[
            UnaryOperators.BIT_NOT[UnaryOperators.NOT[nameRef("b", ErrorType)]]]]]]
        ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Comma Operator`() {
    val p = prepareCode("""
      int main() {
        123 + 124, 35 + 36;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    (123 add 124) comma (35 add 36) assertEquals p.root.decls[0].fn.block.items[0].st
  }

  @Test
  fun `Comma Operator Has Correct Associativity`() {
    val p = prepareCode("""
      int main() {
        123 + 124, 35 + 36, 1 + 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    ((123 add 124) comma (35 add 36)) comma (1 add 2) assertEquals
        p.root.decls[0].fn.block.items[0].st
  }

  @Test
  fun `More Parens Closed Than Opened`() {
    val p = prepareCode("int a = (1 + 2));", source)
    // This can create bullshit diags, so just check it didn't succeed
    assert(p.diags.isNotEmpty())
  }

  @Test
  fun `Enum Value Usage`() {
    val p = prepareCode("""
      enum color {RED, GREEN, BLUE};
      int main() {
        enum color c1 = RED;
        enum color c2 = GREEN;
        enum color c3 = c1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val color = enum("color", "RED", "GREEN", "BLUE").toSpec()
    int func ("main" withParams emptyList()) body compoundOf(
        color declare ("c1" assign nameRef("RED", SignedIntType)),
        color declare ("c2" assign nameRef("GREEN", SignedIntType)),
        color declare ("c3" assign nameRef("c1", SignedIntType))
    ) assertEquals p.root.decls[0]
  }

  @Nested
  inner class TernaryTests {
    @Test
    fun `Ternary Simple`() {
      val p = prepareCode("int a = 1 ? 2 : 3;", source)
      p.assertNoDiagnostics()
      int declare ("a" assign 1.qmark(2, 3)) assertEquals p.root.decls[0]
    }

    @Test
    fun `Ternary String Literals`() {
      val p = prepareCode("const char* s = 1 ? \"foo\" : \"barbaz\";", source)
      p.assertNoDiagnostics()
      val ternary = 1.qmark(strLit("foo"), strLit("barbaz"))
      const + char declare (ptr("s") assign ternary) assertEquals p.root.decls[0]
    }

    @Test
    fun `Ternary Missing Colon`() {
      val p = prepareCode("int a = 1 ? 2 ;", source)
      p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    }

    @Test
    fun `Ternary Missing Question Mark`() {
      val p = prepareCode("int a = 2 : 3;", source)
      p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
    }

    @Test
    fun `Ternary With Parenthesis`() {
      val p = prepareCode("int a = 1 ? (2 + 2) : (3 + 3);", source)
      p.assertNoDiagnostics()
      int declare ("a" assign 1.qmark(2 add 2, 3 add 3)) assertEquals p.root.decls[0]
    }

    @Test
    fun `Ternary Nested Middle`() {
      val p = prepareCode("int a = 1 ? 11 ? 22 : 44 : 3;", source)
      p.assertNoDiagnostics()
      int declare ("a" assign 1.qmark(11.qmark(22, 44), 3)) assertEquals p.root.decls[0]
    }

    @Test
    fun `Ternary Nested Last`() {
      val p = prepareCode("int a = 1 ? 2 : 1 ? 3 : 4;", source)
      p.assertNoDiagnostics()
      int declare ("a" assign 1.qmark(2, 1.qmark(3, 4))) assertEquals p.root.decls[0]
    }

    @Test
    fun `Ternary Bad Assignment`() {
      val p = prepareCode("int a = 1 ? 2 : a = 3;", source)
      p.assertNoDiagnostics()
      val badAssignment = nameRef("a", SignedIntType) assign 3
      int declare ("a" assign 1.qmark(2, badAssignment)) assertEquals p.root.decls[0]
    }
  }

  @Nested
  inner class ArraySubscriptTests {
    @Test
    fun `Subscript Unmatched Parens`() {
      val p = prepareCode("int main() {int a[12]; a[2;}", source)
      p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    }

    @Test
    fun `Subscript Empty`() {
      val p = prepareCode("int main() {int a[12]; a[];}", source)
      p.assertDiags(DiagnosticId.EXPECTED_EXPR)
    }

    @ParameterizedTest
    @ValueSource(strings = ["main[1]", "1[main]", "main[main]", "(*&main)[1]"])
    fun `Subscripts Of Function Must Fail`(subscriptToTest: String) {
      val p = prepareCode("int main() {$subscriptToTest;}", source)
      p.assertDiags(DiagnosticId.SUBSCRIPT_OF_FUNCTION)
    }

    @ParameterizedTest
    @ValueSource(strings = [
      "v[1]", "v[-12]", "v['a']", "v[0]", "v[12L]", "v[1U]", "v[23ULL]", "v[35LL]"])
    fun `Valid Subscripts`(subscriptToTest: String) {
      val p = prepareCode("int main() {int v[123];$subscriptToTest;}", source)
      p.assertNoDiagnostics()
      assert(p.root.decls[0].fn.block.items[1].st is ArraySubscript)
    }

    @ParameterizedTest
    @ValueSource(strings = ["v[v]", "v[main]", "v[\"sdfghsr\"]", "v[&main]"])
    fun `Subscripts That Are Not Integers`(subscriptToTest: String) {
      val p = prepareCode("int main() {int v[123];$subscriptToTest;}", source)
      p.assertDiags(DiagnosticId.SUBSCRIPT_NOT_INTEGRAL)
    }

    @ParameterizedTest
    @ValueSource(strings = ["123[123]", "1.2[123]", "123[1.2]", "'a'[123]"])
    fun `Not Subscriptable`(subscriptToTest: String) {
      val p = prepareCode("int main() {$subscriptToTest;}", source)
      p.assertDiags(DiagnosticId.INVALID_SUBSCRIPTED)
    }

    @Test
    fun `Subscripts Are Char`() {
      val p = prepareCode("""
      int main() {
        char c = 'b';
        int v[200];
        v[c];
      }
    """.trimIndent(), source)
      p.assertDiags(DiagnosticId.SUBSCRIPT_TYPE_CHAR)
    }
  }

  @Nested
  inner class CastOperatorTests {
    @Test
    fun `Simple Cast Expression`() {
      val p = prepareCode("int a = 1; unsigned int b = (unsigned) a;", source)
      p.assertNoDiagnostics()
      uInt declare ("b" assign UnsignedIntType.cast(nameRef("a", SignedIntType))) assertEquals
          p.root.decls[1]
    }

    @Test
    fun `Void Cast Expression`() {
      val p = prepareCode("int main() {(void) (1 + 1);}", source)
      p.assertNoDiagnostics()
      val cast = assertNotNull(p.root.decls[0].fn.block.items[0].st as? CastExpression)
      assert(cast.type is VoidType)
    }

    @Test
    fun `Can't Cast To Non-Scalar`() {
      val p = prepareCode("struct vec2 {int x,y;}; int main() {(struct vec2) (1 + 1);}", source)
      p.assertDiags(DiagnosticId.INVALID_CAST_TYPE)
    }

    @ParameterizedTest
    @ValueSource(strings = [
      "float f = 2.0; (int*) f",
      "int a = 1; (float) (&a)"
    ])
    fun `Can't Cast Between Pointers And Floats`(cast: String) {
      val p = prepareCode("int main() {$cast;}", source)
      p.assertDiags(DiagnosticId.POINTER_FLOAT_CAST)
    }
  }

  @Nested
  inner class MemberAccessTests {
    @ParameterizedTest
    @ValueSource(strings = [
      "u . 1;", "u. 1;", "u. 1.1;", "u..;", "u->.;", "u.->;", "u.#;", "u.,;", "u.&;", "u.sizeof;"
    ])
    fun `Expect Identifier After Punctuator`(accessStr: String) {
      val p = prepareCode("""
        struct vec2 {int x, y;};
        int main() {
          struct vec2 u;
          $accessStr
        }
      """.trimIndent(), source)
      p.assertDiags(DiagnosticId.EXPECTED_IDENT)
    }

    @ParameterizedTest
    @ValueSource(strings = [
      "1 -> a;", "2.0 -> a;", "(1 + 1) -> a;", "-1 -> a;"
    ])
    fun `Expect Pointer Type For Arrow Operator`(accessStr: String) {
      val p = prepareCode("int main() {$accessStr}", source)
      p.assertDiags(DiagnosticId.MEMBER_REFERENCE_NOT_PTR)
    }

    @ParameterizedTest
    @ValueSource(strings = [
      "1 . a;", "2.0 . a;", "(1 + 1) . a;", "-1 . a;"
    ])
    fun `Expect Member Base To Be Tag`(accessStr: String) {
      val p = prepareCode("int main() {$accessStr}", source)
      p.assertDiags(DiagnosticId.MEMBER_BASE_NOT_TAG)
    }

    @ParameterizedTest
    @ValueSource(strings = [
      "u.a;", "u.u;", "u.v;", "u.xy;", "u.vec2;"
    ])
    fun `Member Name Must Exist`(accessStr: String) {
      val p = prepareCode("""
        struct vec2 {int x, y;};
        int main() {
          struct vec2 u;
          $accessStr
        }
      """.trimIndent(), source)
      p.assertDiags(DiagnosticId.MEMBER_NAME_NOT_FOUND)
    }

    @Test
    fun `Member Name Must Exist For Anon Struct`() {
      val p = prepareCode("""
        int main() {
          struct {int x,y;} u;
          u.asdf;
        }
      """.trimIndent(), source)
      p.assertDiags(DiagnosticId.MEMBER_NAME_NOT_FOUND)
    }

    @Test
    fun `Regular Struct Member Access`() {
      val p = prepareCode("""
        struct vec2 {int x, y;};
        int main() {
          struct vec2 u;
          u.x;
        }
      """.trimIndent(), source)
      p.assertNoDiagnostics()
      val vec2 = struct("vec2", int declare listOf("x", "y")).toSpec()
      val u = nameRef("u", typeNameOf(vec2, nameDecl("u")))
      int func ("main" withParams emptyList()) body compoundOf(
          vec2 declare "u",
          u dot nameRef("x", SignedIntType)
      ) assertEquals p.root.decls[0]
    }

    @Test
    fun `Regular Struct Member Access Returns Modifiable Lvalue`() {
      val p = prepareCode("""
        struct vec2 {int x, y;};
        int main() {
          struct vec2 u;
          u.x = 5;
        }
      """.trimIndent(), source)
      p.assertNoDiagnostics()
      val vec2 = struct("vec2", int declare listOf("x", "y")).toSpec()
      val u = nameRef("u", typeNameOf(vec2, nameDecl("u")))
      int func ("main" withParams emptyList()) body compoundOf(
          vec2 declare "u",
          u dot nameRef("x", SignedIntType) assign 5
      ) assertEquals p.root.decls[0]
    }

    @Test
    fun `Struct Member Access To Expr`() {
      val p = prepareCode("""
        struct vec2 {int x, y;};
        int main() {
          struct vec2 u;
          (&u)->x;
        }
      """.trimIndent(), source)
      p.assertNoDiagnostics()
      val vec2 = struct("vec2", int declare listOf("x", "y")).toSpec()
      val u = nameRef("u", typeNameOf(vec2, nameDecl("u")))
      int func ("main" withParams emptyList()) body compoundOf(
          vec2 declare "u",
          UnaryOperators.REF[u] arrow nameRef("x", SignedIntType)
      ) assertEquals p.root.decls[0]
    }
  }
}
