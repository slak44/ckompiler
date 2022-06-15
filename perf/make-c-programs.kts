import java.io.File
import kotlin.random.Random
import kotlin.random.nextInt

val targetDir = File("./generated-c")
targetDir.mkdirs()

val seed = 433
val rng = Random(seed)

val operators = listOf("+", "-", "*", "<", "==")
var nameInt = 0

fun generateExpression(lhs: String, rhs: String): String {
  if (rng.nextBoolean()) {
    val op = operators.random(rng)
    val name = "res$nameInt"
    nameInt++
    return "$name = $lhs $op $rhs;"
  } else {
    return "printf(\"%d %d\", $lhs, $rhs);"
  }
}

fun generateArg(): String {
  if (rng.nextBoolean()) {
    val resNr = rng.nextInt(0 until nameInt.coerceAtLeast(1))
    return "res$resNr"
  } else {
    return rng.nextInt(-200..200).toString()
  }
}

fun generateBlock(depth: Int): String {
  val size = rng.nextInt(2..5)
  var block = ""

  for (i in 1..size) {
    val space = "  ".repeat(depth)
    block += space
    block += generateExpression(generateArg(), generateArg())
    block += "\n"
    if (rng.nextBoolean()) {
      block += "$space// this is a random comment!\n"
    }
  }

  return block
}

fun generateNestedControl(depth: Int): String {
  return if (depth < 100 && rng.nextInt(1..5) > 1) generateControlFlow(depth + 1) else generateBlock(depth + 1)
}

fun generateControlFlow(depth: Int = 0): String = when (rng.nextInt(0..3)) {
  0 -> generateBlock(depth + 1)
  1 -> {
    val arg = generateArg()
    val space = "  ".repeat(depth)
    "${space}if ($arg >= 0) {\n$space${generateNestedControl(depth)}\n$space}"
  }
  2 -> {
    val arg = generateArg()
    val space = "  ".repeat(depth)
    "${space}if ($arg >= 0) {\n${space}${generateNestedControl(depth)}\n${space}} else {\n${space}${generateNestedControl(depth)}\n${space}}"
  }
  3 -> {
    val iterations = rng.nextInt(0..20)
    val space = "  ".repeat(depth)
    "${space}for (i = 0; i < $iterations; i = i + 1) {\n${space}${generateNestedControl(depth)}\n${space}}"
  }
  else -> throw IllegalStateException("unreachable")
}

fun generateDeclarations(): String {
  return (0 until nameInt.coerceAtLeast(1)).joinToString("\n") {
    "int res$it = $it;"
  }
}

fun generateProgram(idx: Int): String {
  val cfg = generateControlFlow()

  return """
    #include <stdio.h>
    
    void f$idx() {
      int i;
      ${generateDeclarations()}
      ${cfg}
    }
  """.trimIndent()
}

fun writeProgram(idx: Int, program: String) {
  val targetFile = File(targetDir, "test$idx.c")
  targetFile.writeText(program)
}

for (i in 1..100) {
  nameInt = 0
  writeProgram(i, generateProgram(i))
}

File(targetDir, "main.c").writeText("int main() { return 0; }")
