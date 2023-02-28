package slak.ckompiler

import slak.ckompiler.lexer.CLIDefines
import slak.ckompiler.parser.*
import kotlin.js.JsExport

/**
 * Machine and ISA-dependent information. Used to generate stuff like stdint/stddef, for sizeof, or
 * for codegen.
 *
 * FIXME: handle alignment requirements
 */
@JsExport
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
    val ptrDiffType: UnqualifiedTypeName,
) {
  private fun Int.toBits(): Int = this * 8

  private val ints = listOf(
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
      ints.firstOrNull { it.first == size }?.also { map["__INT${size.toBits()}_T_TYPE"] = it.second }
      // C standard: 7.20.1.2
      ints.firstOrNull { it.first >= size }?.also {
        map["__INT_LEAST${size.toBits()}_T_TYPE"] = it.second
        map["__INT_LEAST${size.toBits()}_T_SIZE"] = it.first.toBits().toString()
      }
    }
    // C standard: 7.20.1.4
    ints.firstOrNull { it.first == ptrSizeBytes }?.also {
      map["__INTPTR_T_TYPE"] = it.second
      map["__INTPTR_T_SIZE"] = ptrSizeBytes.toBits().toString()
    }
    // C standard: 7.20.1.5
    ints.maxByOrNull { it.first }!!.also {
      map["__INTMAX_T_TYPE"] = it.second
      map["__INTMAX_T_SIZE"] = it.first.toBits().toString()
    }
    // C standard: 7.20.3
    map["__WCHAR_T_SIZE"] = ints.first { it.second == "int" }.first.toBits().toString()
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
    // Function references are broken sometimes, use lambdas here: https://youtrack.jetbrains.com/issue/KT-47767
    is StructureType -> type.members?.map { it.second }?.sumOf { sizeOf(it) } ?: 0
    is UnionType -> type.members?.map { it.second }?.maxByOrNull { sizeOf(it) }?.let { sizeOf(it) } ?: 0
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
    return members.asSequence().takeWhile { it.first != member }.sumOf { sizeOf(it.second) }
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

    val mips32 = MachineTargetData(
        ptrSizeBytes = 4,
        shortSizeBytes = 2,
        intSizeBytes = 4,
        longSizeBytes = 4,
        longLongSizeBytes = 8,
        boolSizeBytes = 4,
        floatSizeBytes = 4,
        doubleSizeBytes = 8,
        longDoubleSizeBytes = 8,
        sizeType = UnsignedIntType,
        ptrDiffType = SignedIntType
    )
  }
}
