package slak.ckompiler.backend

import slak.ckompiler.analysis.*

typealias ValueMapping = List<IRValue>
typealias AdjLists = List<List<Int>>

/**
 * Create live ranges for all the values in the program, and create the interference graph.
 *
 * @return the adjacency lists that make up the graph, and the mapping of [IRValue]s to ints
 */
private fun Sequence<BasicBlock>.interferenceGraph(lists: ISelMap): Pair<ValueMapping, AdjLists> {
  val defs = mutableMapOf<IRValue, Int>()
  val uses = mutableMapOf<IRValue, Int>()
  val irValCounter = IdCounter()
  val valueMap = mutableMapOf<IRValue, Int>().withDefault { irValCounter() }
  val interference = mutableMapOf<Int, MutableList<Int>>().withDefault { mutableListOf() }
  for (block in this) {
    for ((idx, mi) in lists.getValue(block).withIndex()) {
      for ((operand, operandUse) in mi.operands.zip(mi.template.operandUse)) {
        if (operand is ConstantValue) continue
        if (operandUse == VariableUse.DEF || operandUse == VariableUse.DEF_USE) {
          defs[operand] = idx
        }
        if (operandUse == VariableUse.USE || operandUse == VariableUse.DEF_USE) {
          uses[operand] = idx
        }
      }
    }
    for ((variable, _) in block.phiFunctions) {
      defs[variable] = -1
    }
    for (succ in block.successors) for ((variable, _) in succ.phiFunctions) {
      uses[variable] = Int.MAX_VALUE
    }
    for (value in defs.keys) {
      val valueId = valueMap.getValue(value)
      for (otherValue in defs.keys) {
        if (value === otherValue) continue
        val valRange = defs.getValue(value)..uses.getValue(value)
        val otherRange = defs.getValue(otherValue)..uses.getValue(otherValue)
        if (valRange.intersect(otherRange).isNotEmpty()) {
          val otherValueId = valueMap.getValue(otherValue)
          interference.getValue(valueId).add(otherValueId)
          interference.getValue(otherValueId).add(valueId)
        }
      }
    }
    defs.clear()
    uses.clear()
  }
  val adjLists = interference.entries.sortedBy { it.key }.map { it.value }
  val valueMapping = valueMap.entries.sortedBy { it.value }.map { it.key }
  return valueMapping to adjLists
}

private fun MachineTarget.matchValueToRegister(
    value: IRValue,
    registers: List<MachineRegister>,
    forbidden: List<MachineRegister>
): MachineRegister? {
  val validClass = registerClassOf(value.type)
  val validSize = machineTargetData.sizeOf(value.type)
  return (registers - forbidden).firstOrNull { candidate ->
    candidate.valueClass == validClass &&
        (candidate.sizeBytes == validSize || validSize in candidate.aliases.map { it.second })
  }
}

private fun pickSpill(adjLists: AdjLists, valueMapping: ValueMapping): IRValue {
  TODO()
}

private fun insertSpillCode(target: IRValue, iselLists: ISelMap): ISelMap {
  TODO()
}

fun MachineTarget.regAlloc(cfg: CFG, iselLists: ISelMap): Map<IRValue, MachineRegister> {
  val seq = createDomTreePreOrderSequence(cfg.doms, cfg.startBlock, cfg.nodes)
  var instrs = iselLists
  while (true) {
    val (valueMapping, adjLists) = seq.interferenceGraph(instrs)
    val peo = maximumCardinalitySearch(adjLists)
    val coloring = greedyColoring(adjLists, peo, emptyMap()) { node, forbiddenRegisters ->
      matchValueToRegister(valueMapping[node], registers, forbiddenRegisters)
    }
    if (coloring != null) {
      return coloring.withIndex().associate { (node, register) -> valueMapping[node] to register }
    }
    val toSpill = pickSpill(adjLists, valueMapping)
    instrs = insertSpillCode(toSpill, instrs)
  }
}
