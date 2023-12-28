import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlin.random.nextInt

val targetDir = File("./generated-c")
targetDir.mkdirs()

val seed = 134
val rng = Random(seed)

val operators = listOf("+", "-", "*", "<", "==")
var nameInt = 0

var nameStr = 0
val maxStrSize = UUID.randomUUID().toString().length

val arrayName = "array"
val arraySize = rng.nextInt(100..300)

fun generateExpression(): String = when (rng.nextInt(0..4)) {
  0 -> {
    val op = operators.random(rng)
    val name = "res$nameInt"
    nameInt++
    "$name = ${generateArg()} $op ${generateArg()};"
  }
  1 -> {
    val name = "str$nameStr"
    nameStr++
    val index = rng.nextInt(0..<maxStrSize)
    "${generateIntName()} = $name[$index];"
  }
  2 -> {
    "printf(\"%d %d\", ${generateArg()}, ${generateArg()});"
  }
  3 -> {
    val idx = rng.nextInt(0..<arraySize)

    "$arrayName[$idx] = ${generateArg()};"
  }
  4 -> {
    val idx = rng.nextInt(0..<arraySize)

    "${generateIntName()} = $arrayName[$idx];"
  }
  else -> throw IllegalStateException("unreachable")
}

fun generateIntName(): String {
  val resNr = rng.nextInt(0 until nameInt.coerceAtLeast(1))
  return "res$resNr"
}

fun generateStrName(): String {
  val resNr = rng.nextInt(0 until nameStr.coerceAtLeast(1))
  return "res$resNr"
}

fun generateArg(): String {
  return if (rng.nextBoolean()) {
    generateIntName()
  } else {
    rng.nextInt(-200..200).toString()
  }
}

fun generateBlock(depth: Int): String {
  val size = rng.nextInt(5..10)
  var block = ""

  for (i in 1..size) {
    val space = "  ".repeat(depth)
    block += space
    block += generateExpression()
    block += "\n"
    if (rng.nextBoolean()) {
      block += "$space// this is a random comment!\n"
    }
  }

  return block
}

fun generateNestedControl(depth: Int): String {
  return if (depth < 100 && rng.nextInt(1..1000) > 1) generateControlFlow(depth + 1) else generateBlock(depth + 1)
}

fun generateControlFlow(depth: Int = 0): String = when (rng.nextInt(0..4)) {
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
  4 -> {
    val arg = generateArg()
    val space = "  ".repeat(depth)
    "${space}while ($arg != 0) {\n${space}${generateNestedControl(depth)}\n${space}}"
  }
  else -> throw IllegalStateException("unreachable")
}

fun generateDeclarations(): String {
  val ints = (0 until nameInt.coerceAtLeast(1)).joinToString("\n") {
    "int res$it = $it;"
  }

  val strs = (0 until nameStr.coerceAtLeast(1)).joinToString("\n") {
    "const char* str$it = \"${UUID.randomUUID()}\";"
  }

  val array = "int $arrayName[$arraySize];"

  return ints + "\n" + strs + "\n" + array
}

fun generateProgram(idx: Int): String {
  val cfg = generateControlFlow()

  return """
    #include <stdio.h>
    
    void f$idx() {
      int i;
      ${generateDeclarations()}
      $cfg
    }
  """.trimIndent()
}

fun writeProgram(idx: Int, program: String) {
  val targetFile = File(targetDir, "test$idx.c")
  targetFile.writeText(program)
}

for (i in 1..100) {
  nameInt = 0
  nameStr = 0
  writeProgram(i, generateProgram(i))
}

File(targetDir, "main.c").writeText("int main() { return 0; }")
