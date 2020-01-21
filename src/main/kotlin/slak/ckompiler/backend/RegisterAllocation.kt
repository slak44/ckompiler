package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

typealias ValueIndex = Int

/**
 * Maps [IRValue]s to indices.
 */
typealias ValueMapping = List<IRValue>

typealias AdjLists = List<List<ValueIndex>>

data class InterferenceGraph(
    val source: ISelMap,
    val adjLists: AdjLists,
    val valueMapping: ValueMapping
)

/**
 * Create live ranges for all the values in the program, and create the interference graph.
 */
private fun Sequence<BasicBlock>.interferenceGraph(lists: ISelMap): InterferenceGraph {
  val irValCounter = IdCounter()
  val valueMap = mutableMapOf<IRValue, ValueIndex>()
  val interference = mutableMapOf<ValueIndex, MutableList<ValueIndex>>()
  for (block in this) {
    val defs = mutableMapOf<IRValue, Int>()
    val uses = mutableMapOf<IRValue, Int>()
    for ((idx, mi) in lists.getValue(block).withIndex()) {
      for ((operand, operandUse) in mi.operands.zip(mi.template.operandUse)) {
        if (operand is ConstantValue) continue
        when (operandUse) {
          VariableUse.DEF -> defs[operand] = idx + 1
          VariableUse.USE -> uses[operand] = idx
          VariableUse.DEF_USE -> {
            defs[operand] = idx
            uses[operand] = idx
          }
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
      val valueId = irValCounter()
      valueMap[value] = valueId
      interference[valueId] = mutableListOf()
    }
    for (value in defs.keys) {
      val valueId = valueMap.getValue(value)
      for (otherValue in defs.keys) {
        if (value === otherValue || value !in uses || otherValue !in uses) continue
        val valRange = defs.getValue(value)..uses.getValue(value)
        val otherRange = defs.getValue(otherValue)..uses.getValue(otherValue)
        if (valRange.intersect(otherRange).isNotEmpty()) {
          val otherValueId = valueMap.getValue(otherValue)
          interference.getValue(valueId).add(otherValueId)
          interference.getValue(otherValueId).add(valueId)
        }
      }
    }
  }
  val adjLists = interference.entries.sortedBy { it.key }.map { it.value }
  val valueMapping = valueMap.entries.sortedBy { it.value }.map { it.key }
  return InterferenceGraph(lists, adjLists, valueMapping)
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

private fun pickSpill(
    graph: InterferenceGraph,
    alreadySpilled: List<IRValue>
): IRValue {
  // FIXME: this is an initial placeholder implementation
  return (graph.valueMapping - alreadySpilled).first()
}

private fun insertSpillCode(cfg: CFG, target: IRValue, graph: InterferenceGraph): ISelMap {
  val memoryLoc = MemoryReference(cfg.memoryIds(), target.type)
  val newMap = mutableMapOf<BasicBlock, List<MachineInstruction>>()
  for ((_, instrs) in graph.source) {
    val newInstrs = mutableListOf<MachineInstruction>()
    for (i in instrs) {
      val targetIdx = i.operands.indexOf(target)
      if (targetIdx == -1) {
        newInstrs += i
      } else {
        val newOperands = i.operands.toMutableList()
        newOperands[targetIdx] = memoryLoc
        newInstrs += i.copy(operands = newOperands)
      }
    }
  }
  return newMap
}

fun MachineTarget.regAlloc(cfg: CFG, iselLists: ISelMap): Map<IRValue, MachineRegister> {
  val seq = createDomTreePreOrderSequence(cfg.doms, cfg.startBlock, cfg.nodes)
  val spilled = mutableListOf<IRValue>()
  var instrs = iselLists
  while (true) {
    val graph = seq.interferenceGraph(instrs)
    val (_, adjLists, valueMapping) = graph
    val peo = maximumCardinalitySearch(adjLists)
    val coloring = greedyColoring(adjLists, peo, emptyMap()) { node, forbiddenRegisters ->
      matchValueToRegister(valueMapping[node], registers, forbiddenRegisters)
    }
    if (coloring != null) {
      return coloring.withIndex().associate { (node, register) -> valueMapping[node] to register }
    }
    if (spilled.size == valueMapping.size) {
      logger.throwICE("Spilled all the values but still can't color the graph")
    }
    val toSpill = pickSpill(graph, spilled)
    spilled += toSpill
    instrs = insertSpillCode(cfg, toSpill, graph)
  }
}
