package slak.ckompiler

actual class AtomicInteger {
  init {
    allAtomicIdCounters += this
  }

  internal var integer = 0

  // This is JS, so everything is technically atomic
  actual fun getAndIncrement(): Int = integer++

  companion object {
    val allAtomicIdCounters = mutableListOf<AtomicInteger>()
  }
}

typealias SavedAtomics = Map<AtomicInteger, Int>

fun saveAndClearAllAtomicCounters(): SavedAtomics {
  val saved = AtomicInteger.allAtomicIdCounters.associateWith { it.integer }

  clearAllAtomicCounters()

  return saved
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
  AtomicInteger.allAtomicIdCounters.forEach { it.integer = 0 }
}

/**
 * Restore cleared counters to an associated saved value.
 */
@Suppress("NON_EXPORTABLE_TYPE")
@JsExport
fun restoreAllAtomicCounters(saved: SavedAtomics) {
  for ((atomicInt, value) in saved) {
    atomicInt.integer = value
  }
}
