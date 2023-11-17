package slak.ckompiler.irserialize

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.io.DataOutput

@OptIn(ExperimentalSerializationApi::class)
class IREncoder(val output: DataOutput) : AbstractEncoder() {
  override val serializersModule: SerializersModule = EmptySerializersModule()

  override fun encodeBoolean(value: Boolean) = output.writeByte(if (value) 1 else 0)
  override fun encodeByte(value: Byte) = output.writeByte(value.toInt())
  override fun encodeShort(value: Short) = output.writeShort(value.toInt())
  override fun encodeInt(value: Int) = output.writeInt(value)
  override fun encodeLong(value: Long) = output.writeLong(value)
  override fun encodeFloat(value: Float) = output.writeFloat(value)
  override fun encodeDouble(value: Double) = output.writeDouble(value)
  override fun encodeChar(value: Char) = output.writeChar(value.code)
  override fun encodeString(value: String) = output.writeUTF(value)
  override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = output.writeInt(index)

  override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
    encodeInt(collectionSize)
    return this
  }

  override fun encodeNull() = encodeBoolean(false)
  override fun encodeNotNullMark() = encodeBoolean(true)

  override fun encodeValue(value: Any) {
    super.encodeValue(value)
  }
}
