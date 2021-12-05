package slak.ckompiler

actual class AtomicInteger {
  private val jvmAtomicInteger = java.util.concurrent.atomic.AtomicInteger()

  actual fun getAndIncrement(): Int = jvmAtomicInteger.getAndIncrement()
}
