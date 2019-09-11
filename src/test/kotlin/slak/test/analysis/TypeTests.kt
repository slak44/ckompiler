package slak.test.analysis

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

class TypeTests {
  @Test
  fun `Unary Plus And Minus`() {
    val p = prepareCode("int a = -1; int b = +a;", source)
    p.assertNoDiagnostics()
    val a = nameRef("a", SignedIntType)
    int declare ("a" assign UnaryOperators.MINUS[1]) assertEquals p.root.decls[0]
    int declare ("b" assign UnaryOperators.PLUS[a]) assertEquals p.root.decls[1]
  }

  @Test
  fun `Unary Plus And Minus On Bad Type`() {
    val p = prepareCode("int main() {-main;}", source)
    p.assertDiags(DiagnosticId.INVALID_ARGUMENT_UNARY)
    int func ("main" withParams emptyList()) body compoundOf(
        UnaryOperators.MINUS[ErrorExpression()]
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unary Bitwise Not On Bad Type`() {
    val p = prepareCode("int main() {~main;}", source)
    p.assertDiags(DiagnosticId.INVALID_ARGUMENT_UNARY)
    int func ("main" withParams emptyList()) body compoundOf(
        UnaryOperators.BIT_NOT[ErrorExpression()]
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Call An Expr`() {
    val p = prepareCode("""
      int f();
      int a = (&f)();
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val proto = int proto "f"
    proto assertEquals p.root.decls[0]
    val f = fnPtrOf(proto)
    int declare ("a" assign UnaryOperators.REF[f]()) assertEquals p.root.decls[1]
  }

  @Test
  fun `Prefix Increment Bad Type`() {
    val p = prepareCode("int main() {++main;}", source)
    p.assertDiags(DiagnosticId.INVALID_INC_DEC_ARGUMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        prefixInc(ErrorExpression())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Binary Add Bad Types`() {
    val p = prepareCode("int main() {1 + main;}", source)
    p.assertDiags(DiagnosticId.INVALID_ARGS_BINARY)
    val main = int proto ("main" withParams emptyList())
    int func ("main" withParams emptyList()) body compoundOf(
        1 add fnPtrOf(main)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Ternary Bad Types`() {
    val p = prepareCode("""
      struct vec2 {int x,y;} v1;
      int main() {
        1 ? 22 : v1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.INVALID_ARGS_TERNARY)
    val struct = struct("vec2", listOf(int declare listOf("x", "y")))
    struct.toSpec() declare "v1" assertEquals p.root.decls[0]
    int func ("main" withParams emptyList()) body compoundOf(
        1.qmark(22, nameRef("v1", typeNameOf(struct.toSpec(), nameDecl("v1"))))
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Assignment To Cast Not Allowed`() {
    val p = prepareCode("int main() {int x = 1; (long) x = 5;}", source)
    p.assertDiags(DiagnosticId.ILLEGAL_CAST_ASSIGNMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        int declare ("x" assign 1),
        SignedLongType.cast(nameRef("x", SignedIntType)) assign 5
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Assignment To Binary Expression Not Allowed`() {
    val p = prepareCode("int main() {int x; (x + 2) = 5;}", source)
    p.assertDiags(DiagnosticId.EXPRESSION_NOT_ASSIGNABLE)
    int func ("main" withParams emptyList()) body compoundOf(
        int declare "x",
        (nameRef("x", SignedIntType) add 2) assign 5
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Assignment To Constant Not Allowed`() {
    val p = prepareCode("int main() {2 = 5;}", source)
    p.assertDiags(DiagnosticId.CONSTANT_NOT_ASSIGNABLE)
    int func ("main" withParams emptyList()) body compoundOf(
        2 assign 5
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Assignment To Ternary Fails`() {
    val p = prepareCode("""
      int main() {
        int x, y;
        (1 ? x : y) = 2;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPRESSION_NOT_ASSIGNABLE)
    int func ("main" withParams emptyList()) body compoundOf(
        int declare listOf("x", "y"),
        1.qmark(nameRef("x", SignedIntType), nameRef("y", SignedIntType)) assign 2
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Array Of Functions Not Allowed`() {
    val p = prepareCode("int a[123]();", source)
    p.assertDiags(DiagnosticId.INVALID_ARR_TYPE)
    int declare (name("a")[123] withParams emptyList()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Array Of Functions Not Allowed As Declaration Statement`() {
    val p = prepareCode("int main() {int a[123]();}", source)
    p.assertDiags(DiagnosticId.INVALID_ARR_TYPE)
    val badDecl = int declare (name("a")[123] withParams emptyList())
    int func ("main" withParams emptyList()) body compoundOf(badDecl) assertEquals p.root.decls[0]
  }

  @ParameterizedTest
  @ValueSource(strings = ["f(1, 2, 3);", "f(1);", "f();"])
  fun `Wrong Argument Count`(string: String) {
    val p = prepareCode("""
      void f(int a, int b) {}
      int main() {
        $string
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.FUN_CALL_ARG_COUNT)
  }

  @ParameterizedTest
  @ValueSource(strings = ["f(1);", "f();"])
  fun `Wrong Argument Count Variadic`(string: String) {
    val p = prepareCode("""
      void f(int a, int b, ...) {}
      int main() {
        $string
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.FUN_CALL_ARG_COUNT_VAR)
  }

  @Test
  fun `Implicit Casts For Expressions`() {
    val p = prepareCode("""
      int main() {
        12 + 2.2;
        12L + 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        DoubleType.cast(12) add 2.2,
        long(12) add SignedLongType.cast(2)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `No Cast For Float Comparisons`() {
    val p = prepareCode("""
      int main() {
        12.2F < 23.3F;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        float(12.2) less float(23.3)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Compound Assignment Implicit Casts Are Correct`() {
    val p = prepareCode("""
      int main() {
        double d = 123.23;
        float f = 1.0F;
        f += d - 0.23;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        double declare ("d" assign 123.23),
        float declare ("f" assign float(1.0)),
        nameRef("f", FloatType) plusAssign (nameRef("d", DoubleType) sub 0.23)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Implicit Casts For Initializers`() {
    val p = prepareCode("""
      int main() {
        int a = 1.2;
        float f = 12;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        int declare ("a" assign SignedIntType.cast(1.2)),
        float declare ("f" assign FloatType.cast(12))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Implicit Casts For Regular Function Call Arguments`() {
    val p = prepareCode("""
      void f(int sh, double flt) {}
      int main() {
        short sh = 12;
        float flt = 2.0F;
        f(sh, flt);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = void func ("f" withParams listOf(int param "sh", double param "flt")) body
        emptyCompound()
    f assertEquals p.root.decls[0]
    int func ("main" withParams emptyList()) body compoundOf(
        short declare ("sh" assign SignedShortType.cast(12)),
        float declare ("flt" assign float(2.0)),
        f(
            SignedIntType.cast(nameRef("sh", SignedShortType)),
            DoubleType.cast(nameRef("flt", FloatType))
        )
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Implicit Casts For Variadic Function Call Arguments`() {
    val p = prepareCode("""
      void f(int a, ...) {}
      int main() {
        f(1, 2.2F, 3.3F, (char) 23);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = void func ("f" withParamsV listOf(int param "a")) body emptyCompound()
    f assertEquals p.root.decls[0]
    int func ("main" withParams emptyList()) body compoundOf(
        f(
            1,
            DoubleType.cast(float(2.2)),
            DoubleType.cast(float(3.3)),
            SignedIntType.cast(SignedCharType.cast(23))
        )
    ) assertEquals p.root.decls[1]
  }

  @Suppress("unused")
  enum class ArithmeticConversionTestCase(
      val lhs: TypeName,
      val rhs: TypeName,
      val expected: TypeName
  ) {
    SHORT_INT(SignedShortType, SignedIntType, SignedIntType),
    LONG_INT(SignedLongType, SignedIntType, SignedLongType),
    INT_LONG(SignedIntType, SignedLongType, SignedLongType),
    FLT_DOUBLE(FloatType, DoubleType, DoubleType),
    INT_INT(SignedIntType, SignedIntType, SignedIntType),
    FLT_INT(FloatType, SignedIntType, FloatType),
    INT_FLT(SignedIntType, FloatType, FloatType),
    ERROR_INT(ErrorType, SignedIntType, ErrorType)
  }

  @ParameterizedTest
  @EnumSource(value = ArithmeticConversionTestCase::class)
  fun `Usual Arithmetic Conversions`(case: ArithmeticConversionTestCase) {
    val result = usualArithmeticConversions(case.lhs, case.rhs)
    assertEquals(case.expected, result, "Conversion failed")
  }

  @Disabled("have to deal with incomplete structs in type system first")
  @Test
  fun `Sizeof Incomplete Type`() {
    val p = prepareCode("struct x; int a = sizeof(struct x);", source)
    p.assertDiags(DiagnosticId.SIZEOF_ON_INCOMPLETE)
  }

  @Test
  fun `Sizeof Function Type`() {
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
    val v = nameRef("v", ArrayType(SignedIntType, ConstantSize(int(20))))
    int func ("main" withParams emptyList()) body compoundOf(
        int declare nameDecl("v")[20],
        sizeOf(v)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Address Of Array Type Results In Simple Pointer`() {
    val p = prepareCode("""
      int main() {
        int v[20];
        int* x = &v;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val arrayType = ArrayType(SignedIntType, ConstantSize(int(20)))
    val v = nameRef("v", PointerType(SignedIntType, emptyList(), arrayType))
    int func ("main" withParams emptyList()) body compoundOf(
        int declare nameDecl("v")[20],
        int declare (ptr("x") assign UnaryOperators.REF[v])
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Address Of Function Type Results In Simple Pointer`() {
    val p = prepareCode("""
      typedef int mf(void);
      int main() {
        mf* x = &main;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val (typedef, mf) = typedef(int, "mf" withParams emptyList())
    typedef proto "mf" assertEquals p.root.decls[0]
    val main = nameRef("main", FunctionType(SignedIntType, emptyList()))
    int func ("main" withParams emptyList()) body compoundOf(
        mf declare (ptr("x") assign UnaryOperators.REF[main])
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Functions Are Actually Pointers`() {
    val p = prepareCode("""
      typedef int mf(void);
      int main() {
        mf* asdf = main;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val (typedef, mf) = typedef(int, "mf" withParams emptyList())
    typedef proto "mf" assertEquals p.root.decls[0]
    val main = nameRef("main", PointerType(FunctionType(SignedIntType, emptyList()), emptyList()))
    int func ("main" withParams emptyList()) body compoundOf(
        mf declare (ptr("asdf") assign main)
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Indirection On Function Works`() {
    val p = prepareCode("""
      int f() { return 23; }
      int main() {
        int b = (*f)();
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = int func "f" body compoundOf(returnSt(23))
    f assertEquals p.root.decls[0]
    int func ("main" withParams emptyList()) body compoundOf(
        int declare ("b" assign UnaryOperators.DEREF[f]())
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Arrays Are Actually Pointers`() {
    val p = prepareCode("""
      int main() {
        int vec[20];
        int* p = vec;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val vec = nameRef("vec", PointerType(SignedIntType, emptyList()))
    int func ("main" withParams emptyList()) body compoundOf(
        int declare nameDecl("vec")[20],
        int declare (ptr("p") assign vec)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Taking Address Of Rvalue Is Not Allowed`() {
    val p = prepareCode("""
      int main() {
        &123;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.ADDRESS_REQUIRES_LVALUE)
    int func ("main" withParams emptyList()) body compoundOf(
        UnaryOperators.REF[123]
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Taking Address Of Register Variable Is Not Allowed`() {
    val p = prepareCode("""
      int main() {
        register int a;
        int* p = &a;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.ADDRESS_OF_REGISTER)
    int func ("main" withParams emptyList()) body compoundOf(
        int.copy(storageClass = Keywords.REGISTER.kw) declare "a",
        int declare (ptr("p") assign UnaryOperators.REF[nameRef("a", SignedIntType)])
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Taking Address Of Register Array Is Undefined Behaviour`() {
    val p = prepareCode("""
      int main() {
        register int vec[20];
        int* p = vec;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.ADDRESS_OF_REGISTER)
    val vec = nameRef("vec", PointerType(SignedIntType, emptyList()))
    int func ("main" withParams emptyList()) body compoundOf(
        int.copy(storageClass = Keywords.REGISTER.kw) declare nameDecl("vec")[20],
        int declare (ptr("p") assign vec)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Assignment Result Is Not An Lvalue`() {
    val p = prepareCode("""
      int main() {
        int a = 2;
        (a = 3) = 5;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPRESSION_NOT_ASSIGNABLE)
    int func ("main" withParams emptyList()) body compoundOf(
        int declare ("a" assign 2),
        (nameRef("a", SignedIntType) assign 3) assign 5
    ) assertEquals p.root.decls[0]
  }
}
