package slak.ckompiler.analysis

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler

/**
 * Print diagnostics on unreachable code.
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
    block.terminator is ImpossibleJump -> warnAllNodes((block.terminator as ImpossibleJump).target)
    block.terminator is UncondJump -> warnDeadCode((block.terminator as UncondJump).target)
    block.terminator is CondJump -> {
      warnDeadCode((block.terminator as CondJump).target)
      warnDeadCode((block.terminator as CondJump).target)
    }
    block.terminator is ConstantJump -> {
      warnAllNodes((block.terminator as ConstantJump).impossible)
      warnDeadCode((block.terminator as ConstantJump).target)
    }
  }
}
