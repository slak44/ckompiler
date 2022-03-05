package slak.ckompiler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class EnumOrdinalSerializer<E : Enum<E>> : KSerializer<Enum<E>> {
  override val descriptor: SerialDescriptor = Int.serializer().descriptor

  override fun deserialize(decoder: Decoder): Enum<E> {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: Enum<E>) {
    encoder.encodeSerializableValue(
        Int.serializer(),
        value.ordinal + 1
    )
  }
}
