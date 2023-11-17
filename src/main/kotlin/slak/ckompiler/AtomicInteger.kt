package slak.ckompiler

expect class AtomicInteger() {
  fun getAndIncrement(): Int

  fun get(): Int

  fun set(value: Int)
}
