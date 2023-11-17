package slak.ckompiler

actual class AtomicInteger {
  private val jvmAtomicInteger = java.util.concurrent.atomic.AtomicInteger()

  actual fun getAndIncrement(): Int = jvmAtomicInteger.getAndIncrement()

  actual fun get(): Int = jvmAtomicInteger.get()

  actual fun set(value: Int) {
    jvmAtomicInteger.set(value)
  }
}
