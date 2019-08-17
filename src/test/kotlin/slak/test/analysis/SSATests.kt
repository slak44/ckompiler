package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.analysis.*
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SSATests {
  @Test
  fun `SSA Conversion Doesn't Fail 1`() {
    val cfg = prepareCFG(resource("ssa/domsTest.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 2`() {
    val cfg = prepareCFG(resource("ssa/domsTest2.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 3`() {
    val cfg = prepareCFG(resource("ssa/domsTest3.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 4`() {
    val cfg = prepareCFG(resource("ssa/domsTest4.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Diamond Graph From If-Else Has Correct Dominance Frontier`() {
    val cfg = prepareCFG(resource("ssa/trivialDiamondGraphTest.c"), source)
    assert(cfg.startBlock.dominanceFrontier.isEmpty())
    val t = cfg.startBlock.terminator as CondJump
    val ret = cfg.nodes.first { it.terminator is ImpossibleJump }
    assertEquals(setOf(ret), t.target.dominanceFrontier)
    assertEquals(setOf(ret), t.other.dominanceFrontier)
  }

  @Test
  fun `Correct Definition Tracking Test`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source)
    for ((key, value) in cfg.definitions) {
      println("$key (${key.tid.id}) defined in \n\t${value.joinToString("\n\t")}")
    }
    val realDefs = cfg.definitions.filterNot { it.key.tid.name.startsWith("__synthetic") }
    assertEquals(3, realDefs.size)
    val e = realDefs.entries.toList()
    val rootId = cfg.startBlock.nodeId
    fun blockIds(vararg id: Int) = id.map { rootId + it }.toList()
    assertEquals(e[0].value.map { it.nodeId }, blockIds(0, 3, 4, 9))
    assertEquals(e[1].value.map { it.nodeId }, blockIds(0, 3, 4))
    assertEquals(e[2].value.map { it.nodeId }, blockIds(4))
  }

  @Test
  fun `Phi Insertion`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source)
    for (node in cfg.nodes) {
      println("$node φ-functions: \n\t${node.phiFunctions.joinToString("\n\t")}")
    }
    val rootId = cfg.startBlock.nodeId
    fun phis(id: Int) = cfg.nodes.first { it.nodeId == (rootId + id) }.phiFunctions
    fun List<PhiFunction>.x() = firstOrNull { it.target.tid.name == "x" }
    for (i in listOf(1, 2, 9)) assertNotNull(phis(i).x())
    for (i in listOf(0, 3, 4)) assertNull(phis(i).x())
  }

  @Test
  fun `Variable Renaming`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source)
    fun condVarOf(b: BasicBlock) = (((b.terminator as? CondJump)?.cond?.ir?.get(0) as? Store)
        ?.data as? BinaryComputation)?.lhs as? ComputeReference

    fun rhsOf(b: BasicBlock, idx: Int) = (b.irContext.ir[idx] as? Store)?.data
    fun rhsVarOf(b: BasicBlock, idx: Int) = rhsOf(b, idx) as? ComputeReference
    infix fun String.ver(version: Int) = this to version
    fun assertVarState(expected: Pair<String, Int>, actual: ComputeReference?) {
      assertNotNull(actual)
      assertEquals(expected.first, actual.tid.name)
      assertEquals(expected.second, actual.version)
    }

    val firstBlock = cfg.startBlock.successors[0]
    assertVarState("x" ver 1, condVarOf(firstBlock))

    val blockFail1 = firstBlock.successors[1]
    assertVarState("x" ver 1, rhsVarOf(blockFail1, 0))
    assertVarState("y" ver 1, rhsVarOf(blockFail1, 2))
    assertVarState("tmp" ver 2, rhsVarOf(blockFail1, 4))
    assertVarState("x" ver 3, condVarOf(blockFail1))

    val blockFail2 = blockFail1.successors[1]
    val sumAssigned = assertNotNull(rhsOf(blockFail2, 0) as? BinaryComputation)
    assertVarState("x" ver 4, sumAssigned.lhs as? ComputeReference)
    assertVarState("y" ver 4, sumAssigned.rhs as? ComputeReference)
    assertVarState("x" ver 5, condVarOf(blockFail2))

    val returnBlock = blockFail2.successors[1]
    val retVal =
        (returnBlock.terminator as? ImpossibleJump)?.returned?.ir?.get(0) as? ComputeReference
    assertVarState("x" ver 6, retVal)
  }
}
