package slak.ckompiler.analysis

import slak.ckompiler.AtomicId
import slak.ckompiler.parser.*

class IRValueFactory {
  private val intCache = hashMapOf<Long, MutableList<IntConstant>>()
  private val typedIdentifierCache = hashMapOf<AtomicId, TypedIdentifier>()

  fun getVariable(id: AtomicId, version: Int): Variable {
    val tid = typedIdentifierCache.getValue(id)
    return Variable(tid, version)
  }

  fun createTypedIdentifier(tid: TypedIdentifier) {
    typedIdentifierCache[tid.id] = tid
  }

  fun getIntConstant(value: Long, type: IntegralType): IntConstant {
    val cache = intCache.getOrPut(value, ::mutableListOf)
    val typeMatch = cache.find { it.type === type }

    if (typeMatch != null) {
      return typeMatch
    } else {
      val newInt = IntConstant(value, type)
      cache += newInt
      return newInt
    }
  }

  fun getIntConstant(from: IntegerConstantNode): IntConstant {
    return getIntConstant(from.value, from.type)
  }

  fun getIntConstant(from: CharacterConstantNode): IntConstant {
    return getIntConstant(from.char.toLong(), from.type)
  }

  fun getIntConstant(value: Int, type: TypeName): IntConstant {
    check(type is IntegralType)
    return getIntConstant(value.toLong(), type)
  }
}
