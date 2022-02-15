package slak.ckompiler

actual class AtomicInteger {
  init {
    allAtomicIdCounters += this
  }

  private var integer = 0

  // This is JS, so everything is technically atomic
  actual fun getAndIncrement(): Int = integer++

  fun clear() {
    integer = 0
  }

  companion object {
    val allAtomicIdCounters = mutableListOf<AtomicInteger>()
  }
}

/**
 * This functions resets all instances of [AtomicInteger] to 0.
 *
 * Useful for pretty ids (BB0 BB1 are better than BB145 and BB146).
 *
 * It's also a memory leak, since these counters are never cleared, but it's small enough that we can just ignore it.
 */
@JsExport
fun clearAllAtomicCounters() {
  AtomicInteger.allAtomicIdCounters.forEach { it.clear() }
}
