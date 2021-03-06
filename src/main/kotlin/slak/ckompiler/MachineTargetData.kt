package slak.ckompiler

import slak.ckompiler.parser.*

/**
 * Machine and ISA-dependent information. Used to generate stuff like stdint/stddef, for sizeof, or
 * for codegen.
 *
 * FIXME: handle alignment requirements
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
    val longDoubleSizeBytes: Int,
    val sizeType: UnqualifiedTypeName,
    val ptrDiffType: UnqualifiedTypeName
) {
  @SuppressWarnings("MagicNumber")
  private fun Int.toBits(): Int = this * 8

  private val ints = mapOf(
      1 to "char",
      boolSizeBytes to "_Bool",
      shortSizeBytes to "short",
      intSizeBytes to "int",
      longSizeBytes to "long",
      longLongSizeBytes to "long long"
  )

  /**
   * C standard: 7.19
   */
  val stddefDefines: CLIDefines = mapOf(
      "__PTRDIFF_T_TYPE" to ptrDiffType.toString(),
      "__PTRDIFF_T_SIZE" to "${sizeOf(ptrDiffType).toBits()}",
      "__SIZE_T_TYPE" to sizeType.toString(),
      "__SIZE_T_SIZE" to "${sizeOf(sizeType).toBits()}",
      "__MAX_ALIGN_T_TYPE" to "signed int", // FIXME: is this correct?
      "__WCHAR_T_TYPE" to "signed int" // FIXME: is this correct?
  )

  /**
   * C standard: 7.20
   */
  val stdintDefines: CLIDefines by lazy {
    val map = mutableMapOf<String, String>()
    for (size in listOf(1, 2, 4, 8)) {
      // C standard: 7.20.1.1, 7.20.1.3
      ints[size]?.also { map["__INT${size.toBits()}_T_TYPE"] = it }
      // C standard: 7.20.1.2
      ints.entries.firstOrNull { it.key >= size }?.also {
        map["__INT_LEAST${size.toBits()}_T_TYPE"] = it.value
        map["__INT_LEAST${size.toBits()}_T_SIZE"] = it.key.toBits().toString()
      }
    }
    // C standard: 7.20.1.4
    ints[ptrSizeBytes]?.also {
      map["__INTPTR_T_TYPE"] = it
      map["__INTPTR_T_SIZE"] = ptrSizeBytes.toBits().toString()
    }
    // C standard: 7.20.1.5
    ints.maxByOrNull { it.key }!!.also {
      map["__INTMAX_T_TYPE"] = it.value
      map["__INTMAX_T_SIZE"] = it.key.toBits().toString()
    }
    // C standard: 7.20.3
    map["__WCHAR_T_SIZE"] = ints.entries.first { it.value == "int" }.key.toBits().toString()
    // FIXME: 7.20.4 needs function-like macros
    map
  }

  /**
   * This function should return the size in bytes of the [type] given.
   *
   * C standard: 6.5.3.4
   */
  fun sizeOf(type: TypeName): Int = when (type) {
    ErrorType -> 0
    is QualifiedType -> sizeOf(type.unqualified)
    is FunctionType, is PointerType -> ptrSizeBytes
    is ArrayType -> {
      val arrSize = type.size as ConstantArraySize
      // FIXME: validate max array sizes somewhere
      arrSize.asValue.toInt() * sizeOf(type.elementType)
    }
    is BitfieldType -> TODO()
    is StructureType -> type.members?.map { it.second }?.sumBy(::sizeOf) ?: 0
    is UnionType -> type.members?.map { it.second }?.maxByOrNull(::sizeOf)?.let(::sizeOf) ?: 0
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

  // FIXME: handle struct memory layouts better
  //   this is just brute force
  fun offsetOf(tagType: StructureType, member: IdentifierNode): Int {
    val members = requireNotNull(tagType.members)
    return members.asSequence().takeWhile { it.first != member }.sumBy { sizeOf(it.second) }
  }

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
        longDoubleSizeBytes = 16,
        sizeType = UnsignedIntType,
        ptrDiffType = SignedLongType
    )
  }
}
