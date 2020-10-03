package slak.test.backend

import org.junit.jupiter.api.Test
import slak.test.compileAndRun
import slak.test.expect
import slak.test.justExitCode
import slak.test.resource

class X64ConstrainedDivTests {
  @Test
  fun `Multiple Constrained Div Test`() {
    val x = mapOf(2 to -1, -231 to 53, 343 to 7)
    val defs = x.entries.withIndex().joinToString(", ") {
      "x${it.index}D = ${it.value.key}, x${it.index}d = ${it.value.value}"
    }
    val result = x.entries.sumBy { it.key / it.value }
    compileAndRun("""
      int main() {
        int ${defs};
        int res = 0;
        ${x.entries.indices.joinToString("\n") { "res += x${it}D / x${it}d;" }}
        return res;
      }
    """.trimIndent()).justExitCode(result)
  }

  @Test
  fun `Consecutive Constrained Div Test`() {
    val (x1, x2, x3, x4) = listOf(23, 5, 343, 7)
    compileAndRun("""
      int main() {
        int res = 0;
        int x1 = $x1, x2 = $x2;
        res += x1 / x2;
        // x1, x2 are dead here
        int x3 = $x3, x4 = $x4;
        res += x3 / x4;
        return res;
      }
    """.trimIndent()).justExitCode(x1 / x2 + x3 / x4)
  }

  @Test
  fun `Constrained Div Test With Phi Insertion`() {
    val a = 23
    val b = 5
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int b = $b, a = $a;
        int res = 0;
        if (a > b) {
          res = a / b;
          a = 111;
        }
        printf("%d", res);
        return a;
      }
    """.trimIndent()).expect(stdout = (a / b).toString(), exitCode = 111)
  }

  @Test
  fun `Constrained Div Test With Reversed Argument Order`() {
    val a = 23
    val b = 5
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int b = $b, a = $a;
        int res = a / b;
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = (a / b).toString())
  }

  @Test
  fun `Constrained Div Test With Live Through Variable`() {
    val a = 23
    val b = 5
    val lt = 7
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int live_through = $lt;
        int a = $a, b = $b;
        int res = a / b;
        printf("%d", res);
        return live_through;
      }
    """.trimIndent()).expect(stdout = (a / b).toString(), exitCode = lt)
  }

  @Test
  fun `Constrained Div Test With Register Pressure`() {
    val a = 23
    val b = 5
    val lt = 7
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int live_through = $lt;
        int x1 = 1, x2 = 2, x3 = 3, x4 = 4;
        int a = $a, b = $b;
        int res = a / b;
        live_through += x1 + x2 + x3 + x4;
        printf("%d", res);
        return live_through;
      }
    """.trimIndent()).expect(stdout = (a / b).toString(), exitCode = lt + 10)
  }

  @Test
  fun `Constrained Div Test With Unused Variable`() {
    val a = 23
    val b = 5
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int unused = 1234;
        int a = $a, b = $b;
        int res = a / b;
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = (a / b).toString())
  }

  @Test
  fun `Constrained Div Test With Live Through Argument Dividend`() {
    val a = 23
    val b = 5
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int a = $a, b = $b;
        int res = a / b;
        printf("%d", res);
        return a;
      }
    """.trimIndent()).expect(stdout = (a / b).toString(), exitCode = a)
  }

  @Test
  fun `Constrained Div Test With Live Through Argument Divisor`() {
    val a = 23
    val b = 5
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int a = $a, b = $b;
        int res = a / b;
        printf("%d", res);
        return b;
      }
    """.trimIndent()).expect(stdout = (a / b).toString(), exitCode = b)
  }

  @Test
  fun `Constrained Div Test With Constant Dividend`() {
    val a = 23
    val b = 5
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int b = $b;
        int res = $a / b;
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = (a / b).toString())
  }

  @Test
  fun `Constrained Div Test With Constant Divisor`() {
    val a = 23
    val b = 5
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int a = $a;
        int res = a / $b;
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = (a / b).toString())
  }

  @Test
  fun `Constrained Div Test Spilling`() {
    compileAndRun(resource("ssa/spillWIthConstrained.c")).justExitCode(9)
  }
}
