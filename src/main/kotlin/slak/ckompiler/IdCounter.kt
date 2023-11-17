package slak.ckompiler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
@Serializable(with = IdCounter.Serializer::class)
class IdCounter {
  private val counter = AtomicInteger()

  operator fun invoke(): AtomicId = counter.getAndIncrement()

  object Serializer : KSerializer<IdCounter> {
    override val descriptor: SerialDescriptor = Int.serializer().descriptor

    override fun deserialize(decoder: Decoder): IdCounter {
      val value = IdCounter()
      value.counter.set(decoder.decodeInt())

      return value
    }

    override fun serialize(encoder: Encoder, value: IdCounter) {
      encoder.encodeInt(value.counter.get())
    }
  }
}
