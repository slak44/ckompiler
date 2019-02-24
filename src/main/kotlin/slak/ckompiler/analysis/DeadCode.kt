package slak.ckompiler.analysis

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler

/**
 * Print diagnostics on unreachable code.
 * It's recommended that [BasicBlock.collapseIfEmptyRecusively] be called on the graph beforehand.
 * @return [graphRoot]
 */
fun IDebugHandler.warnDeadCode(graphRoot: BasicBlock): BasicBlock {
  warnDeadCodeImpl(graphRoot, mutableListOf())
  return graphRoot
}

private fun IDebugHandler.warnAllNodes(block: BasicBlock) {
  for (deadNode in block.data) diagnostic {
    id = DiagnosticId.UNREACHABLE_CODE
    columns(deadNode.tokenRange)
  }
}

private fun IDebugHandler.warnDeadCodeImpl(block: BasicBlock, nodes: MutableList<BasicBlock>) {
  if (block in nodes) return
  nodes += block
  when {
    block.terminator is UncondJump -> {
      warnDeadCodeImpl((block.terminator as UncondJump).target, nodes)
    }
    block.terminator is ImpossibleJump -> {
      warnAllNodes((block.terminator as ImpossibleJump).target)
    }
    block.terminator is CondJump -> {
      warnDeadCodeImpl((block.terminator as CondJump).target, nodes)
      warnDeadCodeImpl((block.terminator as CondJump).target, nodes)
    }
    block.terminator is ConstantJump -> {
      warnAllNodes((block.terminator as ConstantJump).impossible)
      warnDeadCodeImpl((block.terminator as ConstantJump).target, nodes)
    }
  }
}
