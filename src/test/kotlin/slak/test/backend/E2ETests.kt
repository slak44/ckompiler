package slak.test.backend

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.parser.*
import slak.test.compileAndRun
import slak.test.expect
import slak.test.justExitCode
import slak.test.resource

class E2ETests {
  @ParameterizedTest
  @ValueSource(strings = ["1", "1 2", "1 2 3", "1 2 3 4"])
  fun `Returns Argc`(cmdLine: String) {
    val args = cmdLine.split(" ")
    compileAndRun("int main(int argc) { return argc; }", args).justExitCode(args.size + 1)
  }

  @Test
  fun `Empty Main Returns 0`() {
    compileAndRun(resource("e2e/emptyMain.c")).justExitCode(0)
  }

  @Test
  fun `Exit Code 10`() {
    compileAndRun(resource("e2e/returns10.c")).justExitCode(10)
  }

  @Test
  fun `Exit Code Sum`() {
    compileAndRun(resource("e2e/returns1+1.c")).justExitCode(2)
  }

  @Test
  fun `Simple If With False Condition`() {
    compileAndRun(resource("e2e/simpleIf.c")).justExitCode(0)
  }

  @Test
  fun `If With Variable As Condition`() {
    compileAndRun(resource("e2e/cmpVariable.c")).justExitCode(1)
  }

  @Test
  fun `SSA Reconstruction With Phi Use Rewrite`() {
    compileAndRun(resource("ssa/reconstructionRewritePhi.c")).expect(exitCode = 13, stdout = "01")
  }

  @Test
  fun `SSA Reconstruction With Phi Insertion`() {
    compileAndRun(resource("ssa/reconstructionInsertPhi.c")).expect(exitCode = 3, stdout = "")
  }

  @Test
  fun `Hello World!`() {
    compileAndRun(resource("e2e/helloWorld.c")).expect(stdout = "Hello World!\n")
  }

  @Test
  fun `Float Ops Test`() {
    compileAndRun(resource("e2e/floatOps.c")).justExitCode(0)
  }

  @ParameterizedTest
  @ValueSource(strings = ["!0", "!(1-1)"])
  fun `Unary Not`(code: String) {
    compileAndRun("int main() { return $code; }").justExitCode(1)
  }

  @ParameterizedTest
  @ValueSource(strings = ["(int) 1.1F", "(int) 1.0F", "(int) 1.99F"])
  fun `Float Cast To Int`(code: String) {
    compileAndRun("int main() { return $code; }").justExitCode(1)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "123", "-1"])
  fun `Int Cast To Float`(int: String) {
    compileAndRun("""
      extern int printf(const char*, ...);
      int main() {
        printf("%.2f", (float) $int);
        return 0;
      }
    """.trimIndent()).expect(stdout = "$int.00")
  }

  @Test
  fun `Int Pointers Referencing And Dereferencing`() {
    compileAndRun("""
      int main() {
        int a = 12;
        int* b = &a;
        int c = *b;
        return c;
      }
    """.trimIndent()).justExitCode(12)
  }

  @Test
  fun `Int Pointers Referencing And Dereferencing Between Blocks`() {
    compileAndRun("""
      int main() {
        int a = 12;
        int* b = &a;
        if (1) {
          *b = 33;
        } else {
          *b = 44;
        }
        return a;
      }
    """.trimIndent()).justExitCode(33)
  }

  @Test
  fun `Return Int Via Pointer`() {
    compileAndRun(resource("e2e/calls/retViaPointer.c")).justExitCode(42)
  }

  @Test
  fun `Identical String Literals Compare Equal Because Deduplication Or Folding`() {
    compileAndRun("""
      int main() {
        return "asdfg" == "asdfg";
      }
    """.trimIndent()).justExitCode(1)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "a:.><", "       ", "\uFEFE", ">>>>>>>>>>",
    "-------", "#define", "#", "!", "?", "'", "'''''''''''"
  ])
  fun `Printf Random Non-Alphanumerics`(str: String) {
    compileAndRun("""
      #include <stdio.h>
      int main() {
        printf("$str");
        return 0;
      }
    """.trimIndent()).expect(stdout = str)
  }

  @Disabled("arrays are broken in codegen")
  @Test
  fun `Simple Array Usage`() {
    compileAndRun("""
      int main() {
        int a[2];
        a[0] = 12;
        a[1] = 13;
        return a[0] + a[1];
      }
    """.trimIndent()).justExitCode(25)
  }

  @Test
  fun `Nested Expression`() {
    compileAndRun("int main() { return (2 + 3) * (6 - 4); }").justExitCode(10)
  }

  @Test
  fun `Multiple Declarators`() {
    compileAndRun("int main() { int a = 1, b = 2; return a + b; }").justExitCode(3)
  }

  @Test
  fun `Ternary Test`() {
    compileAndRun(resource("e2e/ternaryOps.c")).justExitCode(13)
  }

  @Test
  fun `Shortcircuiting Test`() {
    compileAndRun(resource("e2e/shortCircuiting.c")).justExitCode(55)
  }

  @Test
  fun `Live-Through If Statement`() {
    compileAndRun(resource("e2e/liveThroughIf.c")).justExitCode(66)
  }

  @Test
  fun `Basic Function Call`() {
    compileAndRun(resource("e2e/calls/basicFunctionCall.c")).justExitCode(4)
  }

  @Test
  fun `Function Call With Caller-Saved Variable`() {
    compileAndRun(resource("e2e/calls/callerSavedFunctionCall.c")).justExitCode(7)
  }

  @Test
  fun `Function Call With Callee-Saved Variable Without Red Zone`() {
    compileAndRun {
      file = resource("e2e/calls/calleeSaved.c")
      cliArgList = listOf("-mno-red-zone")
    }.justExitCode(40)
  }

  @Test
  fun `Function Call With Callee-Saved Variable With Red Zone`() {
    compileAndRun {
      file = resource("e2e/calls/calleeSaved.c")
      cliArgList = listOf()
    }.justExitCode(40)
  }

  @Test
  fun `Function Calls With Reused Variable`() {
    compileAndRun(resource("e2e/calls/reuseThroughCalls.c")).justExitCode(1)
  }

  @Test
  fun `Function Call In If`() {
    compileAndRun(resource("e2e/calls/ifCall.c")).expect(exitCode = 2, stdout = "2")
  }

  @Test
  fun `Function With Params On The Stack`() {
    compileAndRun(resource("e2e/stackParams.c")).justExitCode(6)
  }

  @Suppress("unused")
  enum class AddDifferentTypes(val type1: TypeName, val type2: TypeName) {
    INT_LONG(SignedIntType, SignedLongType),
    LONG_INT(SignedLongType, SignedIntType),
    SHORT_INT(SignedShortType, SignedIntType),
    SHORT_LONG(SignedShortType, SignedLongType),
    UINT_INT(UnsignedIntType, SignedIntType),
    CHAR_LONG(SignedCharType, SignedLongType),
    BOOL_CHAR(BooleanType, SignedCharType)
  }

  @ParameterizedTest
  @EnumSource(AddDifferentTypes::class)
  fun `Add Different Types`(test: AddDifferentTypes) {
    compileAndRun("""
      int main() {
        ${test.type1} a = 1;
        ${test.type2} b = 10;
        return a + b;
      }
    """.trimIndent()).justExitCode(11)
  }

  @Test
  fun `Plus Assign Different Types`() {
    compileAndRun("""
      int main() {
        long a = 1;
        a += 2;
        return a;
      }
    """.trimIndent()).justExitCode(3)
  }

  @Test
  fun `Upcast Int To Long`() {
    compileAndRun(resource("e2e/upcastIntLong.c")).expect(stdout = "123")
  }

  @Test
  fun `Initialize Bool With Int`() {
    compileAndRun("""
      int main() {
        _Bool m = 1;
        return m;
      }
    """.trimIndent()).justExitCode(1)
  }

  @Test
  fun `For Loop Summing Test`() {
    compileAndRun(resource("loops/forLoopTest.c")).justExitCode(86)
  }

  @Test
  fun `Looped Printf`() {
    compileAndRun(resource("loops/loopedPrintf.c")).expect(stdout = "0 1 4 9 16 25 36 49 64 81 \n")
  }

  @Test
  fun `Early Return In Void Function Works`() {
    compileAndRun(resource("e2e/calls/earlyReturn.c")).justExitCode(0)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "-2", "100", "9999999", "0"])
  fun `Scanf An Int`(int: String) {
    compileAndRun {
      file = resource("e2e/scanfIntOnce.c")
      stdin = int
    }.expect(stdout = int)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1 3", "-2 4", "100 10000", "9999999 -1", "0 0", "0 1", "1 0"])
  fun `Scanf Multiple Ints`(int: String) {
    compileAndRun {
      file = resource("e2e/scanfIntTwice.c")
      stdin = int
    }.expect(stdout = int)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "1 454 786 -345 0 5 34 -11 99 1010 -444444",
    "0 0 0 0 0 0 0 0 0 0 0",
    "-1 -1 -1 -1 -1 -1 -1 -1 -1 -1 -1"
  ])
  fun `Lots Of Int Parameters`(int: String) {
    compileAndRun {
      file = resource("e2e/calls/manyIntParameters.c")
      stdin = int
    }.expect(stdout = int)
  }

  @Test
  fun `Lots Of Int Parameters And Calls`() {
    compileAndRun(resource("e2e/calls/manyParamsAndCalls.c")).justExitCode(1)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "-1.0000", "-2.0000", "100.0000", "9999999.0000", "0.0000",
    "-1.2000", "23.2351", "1.1000", "0.3000"
  ])
  fun `Scanf A Float`(flt: String) {
    compileAndRun {
      file = resource("e2e/scanfFloatOnce.c")
      stdin = flt
    }.expect(stdout = flt)
  }

  @Suppress("unused")
  enum class InfNaNTestCases(val expr: String, val output: String) {
    INF_EXPR("1.0 / 0.0", "inf"),
    NAN_EXPR("0.0 / 0.0", "nan"),
    NEG_NAN_EXPR("-0.0 / 0.0", "nan"),
    NEG_INF_EXPR("-1.0 / 0.0", "-inf"),
    MACRO_INF_EXPR("INFINITY", "inf"),
    NEG_MACRO_INF_EXPR("-INFINITY", "-inf"),
  }

  @ParameterizedTest
  @EnumSource(InfNaNTestCases::class)
  fun `Print Infinities And NaNs`(test: InfNaNTestCases) {
    compileAndRun("""
      #include <stdio.h>
      #include <math.h>
      int main() {
        double res = ${test.expr};
        printf("%f", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = test.output)
  }

  @ParameterizedTest
  @ValueSource(strings = ["123.456", "0.000", "-1.347", "-9999.333"])
  fun `Printf Doubles`(value: String) {
    compileAndRun("""
      #include <stdio.h>
      #include <math.h>
      int main() {
        double res = $value;
        printf("%.3f", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = value)
  }

  companion object {
    private const val RES = 2
    private const val A = 5
    private const val B = 23
  }

  @Suppress("unused")
  enum class IntOpsTestCases(val code: String, private val result: Int) {
    INT_ADD("res = a + b;", A + B),
    INT_SUB("res = a - b;", A - B),
    INT_MUL("res = a * b;", A * B),
    INT_DIV("res = b / a;", B / A),
    INT_REM("res = b % a;", B % A),

    INT_NEG("res = -b;", -B),
    INT_NOT("res = !b;", 0),

    INT_EQ("res = a == b;", 0),
    INT_NEQ("res = a != b;", 1),
    INT_LEQ("res = a <= b;", 1);

    val output get() = result.toString()
  }

  @ParameterizedTest
  @EnumSource(IntOpsTestCases::class)
  fun `Int Operation Tests`(test: IntOpsTestCases) {
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int a = $A;
        int b = $B;
        int res;
        ${test.code}
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = test.output)
  }

  @Suppress("unused")
  enum class SimplifiableIntOpTestCases(val code: String, private val result: Int) {
    COMM_ADD_LHS("res = res + b;", RES + B),
    COMM_ADD_RHS("res = b + res;", B + RES),

    NOT_COMM_SUB_LHS("res = res - b;", RES - B),
    NOT_COMM_SUB_RHS("res = b - res;", B - RES),

    MUL_RHS("res = b * res;", B * RES),
    MUL_IMM("res = b * 123;", B * 123),
    MUL_IMM_LARGE("res = b * 123L;", B * 123);

    val output get() = result.toString()
  }

  @ParameterizedTest
  @EnumSource(SimplifiableIntOpTestCases::class)
  fun `Int Operation Tests`(test: SimplifiableIntOpTestCases) {
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int a = $A;
        int b = $B;
        int res = $RES;
        ${test.code}
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = test.output)
  }

  @Test
  fun `Enum Value Usage As Return`() {
    compileAndRun("""
      enum color {RED, GREEN, BLUE};
      int main() {
        enum color c1 = BLUE;
        enum color c2 = GREEN;
        enum color c3 = c1;
        return c3;
      }
    """.trimIndent()).justExitCode(2)
  }

  @Test
  fun `Constrained Spilling`() {
    compileAndRun(resource("e2e/spillWithConstrained.c")).justExitCode(9)
  }

  @Test
  fun `Spill Existing Variables For Caller-Saved Registers`() {
    compileAndRun(resource("e2e/calls/spillExistingForCall.c")).expect(exitCode = 7, stdout = "2 8")
  }

  @Test
  fun `Constrained In Large Loop`() {
    compileAndRun(resource("e2e/calls/callInLargeLoopCFG.c")).justExitCode(1)
  }

  @Test
  fun `High Nesting`() {
    compileAndRun(resource("e2e/highNesting.c")).justExitCode(28)
  }

  @Test
  fun `High Nesting With Constrained Calls`() {
    compileAndRun(resource("e2e/calls/callInNestedBlocks.c")).justExitCode(1)
  }

  @Test
  fun `Small Nesting With Constrained Calls`() {
    compileAndRun(resource("e2e/calls/callInNestedBlocksSimple.c")).justExitCode(2)
  }
}
