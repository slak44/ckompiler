package slak.ckompiler.irserialize

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.io.DataInput

@OptIn(ExperimentalSerializationApi::class)
class IRDecoder(private val input: DataInput) : AbstractDecoder() {
  private val elementCounts = mutableListOf<Int>()
  private val elementIndices = mutableListOf<Int>()

  override val serializersModule: SerializersModule = EmptySerializersModule()

  override fun decodeBoolean(): Boolean = input.readByte().toInt() != 0
  override fun decodeByte(): Byte = input.readByte()
  override fun decodeShort(): Short = input.readShort()
  override fun decodeInt(): Int = input.readInt()
  override fun decodeLong(): Long = input.readLong()
  override fun decodeFloat(): Float = input.readFloat()
  override fun decodeDouble(): Double = input.readDouble()
  override fun decodeChar(): Char = input.readChar()
  override fun decodeString(): String = input.readUTF()
  override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = input.readInt()

  override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
    val elementIndex = elementIndices.last()
    if (elementIndex == elementCounts.last()) {
      return CompositeDecoder.DECODE_DONE
    }
    elementIndices[elementIndices.lastIndex] = elementIndex + 1
    return elementIndex
  }

  override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
    elementCounts += descriptor.elementsCount
    elementIndices += 0
    return this
  }

  override fun endStructure(descriptor: SerialDescriptor) {
    elementCounts.removeLast()
    elementIndices.removeLast()
  }

  override fun decodeSequentially(): Boolean = true

  override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = decodeInt().also {
    elementCounts[elementCounts.lastIndex] = it
  }

  override fun decodeNotNullMark(): Boolean = decodeBoolean()
}
