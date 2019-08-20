package slak.ckompiler

import slak.ckompiler.parser.*
import java.lang.IllegalArgumentException

/**
 * Machine and ISA-dependent information. Used to generate stuff like stdint/stddef, for sizeof, or
 * for codegen.
 */
data class MachineTargetData(
    val ptrSizeBytes: Int,
    val shortSizeBytes: Int,
    val intSizeBytes: Int,
    val longSizeBytes: Int,
    val longLongSizeBytes: Int,
    val boolSizeBytes: Int,
    val floatSizeBytes: Int,
    val doubleSizeBytes: Int,
    val longDoubleSizeBytes: Int
) {
  /**
   * This function should return the size in bytes of the [type] given.
   *
   * C standard: 6.5.3.4
   */
  fun sizeOf(type: TypeName): Int = when (type) {
    ErrorType -> 0
    is FunctionType, is PointerType -> ptrSizeBytes
    is ArrayType -> TODO()
    is BitfieldType -> TODO()
    is StructureType -> type.members?.sumBy(::sizeOf) ?: 0
    is UnionType -> type.optionTypes?.maxBy(::sizeOf)?.let(::sizeOf) ?: 0
    SignedCharType, UnsignedCharType -> 1
    SignedShortType, UnsignedShortType -> shortSizeBytes
    SignedIntType, UnsignedIntType -> intSizeBytes
    SignedLongType, UnsignedLongType -> longSizeBytes
    SignedLongLongType, UnsignedLongLongType -> longLongSizeBytes
    BooleanType -> boolSizeBytes
    FloatType -> floatSizeBytes
    DoubleType -> doubleSizeBytes
    LongDoubleType -> longDoubleSizeBytes
    VoidType -> throw IllegalArgumentException("Trying to get size of void type")
  }

  /** @see sizeOf */
  fun sizeOf(tid: TypedIdentifier) = sizeOf(tid.type)

  companion object {
    val x64 = MachineTargetData(
        ptrSizeBytes = 8,
        shortSizeBytes = 4,
        intSizeBytes = 4,
        longSizeBytes = 8,
        longLongSizeBytes = 16,
        boolSizeBytes = 4,
        floatSizeBytes = 4,
        doubleSizeBytes = 8,
        longDoubleSizeBytes = 16
    )
  }
}
