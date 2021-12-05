package slak.ckompiler

/**
 * Unique integer ID. If two objects are tagged with the same ID, they generally compare equal.
 *
 * @see IdCounter
 */
typealias AtomicId = Int

/**
 * Returns a sequential integer ID on [invoke].
 *
 * This operation is atomic. If multiple threads access this value in parallel, each thread's IDs
 * will not be sequential (but they will be distinct).
 */
class IdCounter {
  private val counter = AtomicInteger()
  operator fun invoke(): AtomicId = counter.getAndIncrement()
}
