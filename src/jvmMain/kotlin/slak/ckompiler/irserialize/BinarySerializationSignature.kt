package slak.ckompiler.irserialize

val binaryFormatRevision: UByte = 1u

@OptIn(ExperimentalUnsignedTypes::class)
val binarySerializationSignature = ubyteArrayOf(0x01u, 0xFFu, *"CKI IR".encodeToByteArray().toUByteArray(), binaryFormatRevision)

@OptIn(ExperimentalUnsignedTypes::class)
fun startsWithBinarySignature(bytes: ByteArray): Boolean {
  if (bytes.size < binarySerializationSignature.size) {
    return false
  }

  val startBytes = bytes.slice(binarySerializationSignature.indices).toByteArray().toUByteArray()

  return startBytes.contentEquals(binarySerializationSignature)
}
