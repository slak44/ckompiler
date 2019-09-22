package slak.test.parser

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.parser.*
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
      long int f = 6;
      int long g = 7;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    assertEquals(listOf(
        int declare ("a" assign 1),
        longLong declare ("b" assign SignedLongLongType.cast(2)),
        uLongLong declare ("c" assign UnsignedLongLongType.cast(3)),
        longDouble declare ("d" assign LongDoubleType.cast(4)),
        signedChar declare ("e" assign SignedCharType.cast(5)),
        long declare ("f" assign SignedLongType.cast(6)),
        long declare ("g" assign SignedLongType.cast(7))
    ), p.root.decls)
  }

  @Test
  fun `Multiple Declaration Specifiers`() {
    val p = prepareCode("const static int a;", source)
    p.assertNoDiagnostics()
    val spec = DeclarationSpecifier(
        typeQualifiers = listOf(Keywords.CONST.kw),
        storageClass = Keywords.STATIC.kw,
        typeSpec = IntType(Keywords.INT.kw))
    assertEquals(listOf(spec declare "a"), p.root.decls)
  }

  @Test
  fun `Incompatible Int Int`() {
    // Clang doesn't warn on this, it errors, so we copy them
    val p = prepareCode("int int a = 1;", source)
    p.assertDiags(DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun `Incompatible Typedef With Int`() {
    val p = prepareCode("""
      typedef int const unsigned nice_int;
      nice_int int x;
    """.trimIndent(), source)
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
    assert((p.root.decls[0] as Declaration).declSpecs.typeSpec is VoidTypeSpec)
  }

  @Test
  fun `Inline And Noreturn Allowed`() {
    val p = prepareCode("""
      inline _Noreturn void f() {}
    """.trimIndent(), source)
    p.assertNoDiagnostics()
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

  @Test
  fun `Function Parameters Storage Class Can Only Be Register`() {
    val p = prepareCode("""
      int f(int x, register int y, auto int z, _Thread_local int a, extern int b, static int c);
    """.trimIndent(), source)
    p.assertDiags(*Array(4) { DiagnosticId.ILLEGAL_STORAGE_CLASS })
  }

  @Test
  fun `Typedef Can't Have Initializers`() {
    val p = prepareCode("""
      typedef unsigned int blabla = 23;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.TYPEDEF_NO_INITIALIZER)
  }

  @Test
  fun `Typedef As Type Specifier For External Declaration`() {
    val p = prepareCode("""
      typedef const unsigned int special_int;
      special_int x = 213;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val (typedef, specialIntType) =
        typedef(uInt.copy(typeQualifiers = const), nameDecl("special_int"))
    typedef declare "special_int" assertEquals p.root.decls[0]
    specialIntType declare ("x" assign const(UnsignedIntType).cast(213)) assertEquals
        p.root.decls[1]
  }

  @Test
  fun `Typedef As Type Specifier For Declaration Statement In Inner Scope`() {
    val p = prepareCode("""
      typedef unsigned int special_int;
      int main() {
        special_int x = 213;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val (typedef, specialIntType) = typedef(uInt, nameDecl("special_int"))
    typedef declare "special_int" assertEquals p.root.decls[0]
    int func "main" body compoundOf(
        specialIntType declare ("x" assign UnsignedIntType.cast(213))
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Typedef On Function Prototype`() {
    val p = prepareCode("typedef int f();", source)
    p.assertNoDiagnostics()
    val (typedef, _) = typedef(int, "f" withParams emptyList())
    typedef proto ("f" withParams emptyList()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Typedef On Function Definition Is Not Allowed`() {
    val p = prepareCode("typedef int f() {}", source)
    p.assertDiags(DiagnosticId.FUNC_DEF_HAS_TYPEDEF)
    val (typedef, _) = typedef(int, "f" withParams emptyList())
    typedef func ("f" withParams emptyList()) body emptyCompound() assertEquals p.root.decls[0]
  }

  @Test
  fun `Typedef With Suffixes On Declarator`() {
    val p = prepareCode("""
      typedef int my_func(double, double);
      int my_func_implementation(double x, double y) {
        return 9;
      }
      int main() {
        my_func* fptr = &my_func_implementation;
        int x = fptr(1.1, 7.4);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val myFuncDecl = "my_func" withParams listOf(double.toParam(), double.toParam())
    val (tdef, myFun) = typedef(int, myFuncDecl)
    tdef proto myFuncDecl assertEquals p.root.decls[0]
    val funcImpl = int func ("my_func_implementation" withParams
        listOf(double param "x", double param "y")) body compoundOf(
        returnSt(9)
    )
    funcImpl assertEquals p.root.decls[1]
    val ptrType = PointerType(typeNameOf(myFun, myFuncDecl), emptyList())
    val fptrRef = nameRef("fptr", ptrType)
    int func ("main" withParams emptyList()) body compoundOf(
        myFun declare (ptr("fptr") assign UnaryOperators.REF[funcImpl.toRef()]),
        int declare ("x" assign fptrRef(1.1, 7.4))
    )
  }
}
