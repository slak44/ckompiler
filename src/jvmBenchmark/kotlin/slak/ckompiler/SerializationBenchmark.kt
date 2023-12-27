@file:OptIn(ExperimentalUnsignedTypes::class)

package slak.ckompiler

import org.openjdk.jmh.annotations.*
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.external.CFGSerializer
import slak.ckompiler.irserialize.IRDecoder
import slak.ckompiler.irserialize.IREncoder
import slak.ckompiler.irserialize.binarySerializationSignature
import slak.test.prepareCFG
import java.io.*
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@Fork(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
open class SerializationBenchmark {
  private lateinit var helloWorldCFG: CFG
  private lateinit var randomGeneratedCFG: CFG
  private lateinit var emptyCFG: CFG
  private lateinit var veryLargeCFG: CFG

  private lateinit var helloWorldBytes: ByteArray
  private lateinit var randomGeneratedBytes: ByteArray
  private lateinit var emptyBytes: ByteArray
  private lateinit var veryLargeBytes: ByteArray

  @Setup
  fun setup() {
    val manyHelloWorld = File("src/jvmBenchmark/resources/manyHelloWorld.c")
    helloWorldCFG = prepareCFG(manyHelloWorld, manyHelloWorld.name).create()
    helloWorldBytes = serialize(helloWorldCFG)

    val randomGenerated = File("src/jvmBenchmark/resources/randomGenerated.c")
    randomGeneratedCFG = prepareCFG(randomGenerated, randomGenerated.name).create()
    randomGeneratedBytes = serialize(randomGeneratedCFG)

    val emptyMain = File("src/jvmBenchmark/resources/emptyMain.c")
    emptyCFG = prepareCFG(emptyMain, emptyMain.name).create()
    emptyBytes = serialize(emptyCFG)

    val veryLarge = File("src/jvmBenchmark/resources/veryLargeCFG.c")
    veryLargeCFG = prepareCFG(veryLarge, veryLarge.name).create()
    veryLargeBytes = serialize(veryLargeCFG)
  }

  private fun serialize(cfg: CFG): ByteArray {
    val outputStream = ByteArrayOutputStream()
    val dos = DataOutputStream(outputStream)
    for (byte in binarySerializationSignature) {
      dos.writeByte(byte.toInt())
    }
    IREncoder(dos).encodeSerializableValue(CFGSerializer(), cfg)
    dos.flush()

    return outputStream.toByteArray()
  }

  private fun deserialize(bytes: ByteArray): CFG {
    val cfgBytes = bytes.sliceArray(binarySerializationSignature.size..<bytes.size)
    val dis = DataInputStream(ByteArrayInputStream(cfgBytes))

    return IRDecoder(dis).decodeSerializableValue(CFGSerializer())
  }

  @Benchmark
  fun serializeHelloWorld(): ByteArray {
    return serialize(helloWorldCFG)
  }

  @Benchmark
  fun deserializeHelloWorld(): CFG {
    return deserialize(helloWorldBytes)
  }

  @Benchmark
  fun serializeRandomGenerated(): ByteArray {
    return serialize(randomGeneratedCFG)
  }

  @Benchmark
  fun deserializeRandomGenerated(): CFG {
    return deserialize(randomGeneratedBytes)
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  fun serializeEmpty(): ByteArray {
    return serialize(emptyCFG)
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  fun deserializeEmpty(): CFG {
    return deserialize(emptyBytes)
  }

  @Benchmark
  fun serializeVeryLarge(): ByteArray {
    return serialize(veryLargeCFG)
  }

  @Benchmark
  fun deserializeVeryLarge(): CFG {
    return deserialize(veryLargeBytes)
  }
}
