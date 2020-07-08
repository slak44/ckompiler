package slak.test.backend

import org.junit.jupiter.api.Test
import slak.test.compileAndRun
import slak.test.expect

class X64ConstrainedDivTests {
  @Test
  fun `Multiple Constrained Div Test`() {
    val x = listOf(1, 23, 5, 6, 87, -2345, -34, 22, 65, 9)
    val result = x.indices.windowed(2).map { it[0] / it[1] }.sum()
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int ${x.withIndex().joinToString(", ") { "x${it.index} = ${it.value}" }};
        int res = 0;
        ${x.indices.windowed(2).joinToString("\n") { "res += x${it[0]} / x${it[1]};" }}
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = result.toString())
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
}
