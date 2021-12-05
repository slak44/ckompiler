package slak.ckompiler

actual class AtomicInteger {
  private var integer = 0

  // This is JS, so everything is technically atomic
  actual fun getAndIncrement(): Int = integer++
}
