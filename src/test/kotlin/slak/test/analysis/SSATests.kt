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
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 2`() {
    val cfg = prepareCFG(resource("ssa/domsTest2.c"), source)
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 3`() {
    val cfg = prepareCFG(resource("ssa/domsTest3.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 4`() {
    val cfg = prepareCFG(resource("ssa/domsTest4.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.src.isNotEmpty())
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
    val realDefs = cfg.definitions
    assertEquals(3, realDefs.size)
    val e = realDefs.values.toList()
    assertEquals(e[0].map { it.postOrderId }, listOf(5, 2, 3, 1))
    assertEquals(e[1].map { it.postOrderId }, listOf(5, 2, 3))
    assertEquals(e[2].map { it.postOrderId }, listOf(3))
  }

  @Test
  fun `Phi Insertion`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source)
    for (node in cfg.nodes) {
      println("$node φ-functions: \n\t${node.phiFunctions.joinToString("\n\t")}")
    }
    fun phis(id: Int) = cfg.nodes.first { it.postOrderId == id }.phiFunctions
    fun List<PhiInstr>.x() = firstOrNull { it.variable.tid.name == "x" }
    for (i in listOf(4, 0, 1)) assertNotNull(phis(i).x())
    for (i in listOf(5, 2, 3)) assertNull(phis(i).x())
  }

  @Test
  fun `Variable Renaming`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source)

    fun getLoadTarget(list: List<IRInstruction>?, at: Int) = (list?.get(at) as? LoadInstr)?.target
    fun BasicBlock.getLoadTarget(at: Int) = getLoadTarget(ir, at)
    fun condVarOf(b: BasicBlock) = getLoadTarget((b.terminator as? CondJump)?.cond, 0)
    infix fun String.ver(version: Int) = this to version
    fun assertVarState(expected: Pair<String, Int>, actual: Variable?) {
      assertNotNull(actual)
      assertEquals(expected.first, actual.tid.name)
      assertEquals(expected.second, actual.version)
    }

    val firstBlock = cfg.startBlock.successors[0]
    assertVarState("x" ver 1, condVarOf(firstBlock))

    val blockFail1 = firstBlock.successors[1]
    assertVarState("x" ver 1, blockFail1.getLoadTarget(0))
    assertVarState("y" ver 1, blockFail1.getLoadTarget(2))
    assertVarState("tmp" ver 2, blockFail1.getLoadTarget(4))
    assertVarState("x" ver 3, condVarOf(blockFail1))

    val blockFail2 = blockFail1.successors[1]
    assertVarState("x" ver 4, blockFail2.getLoadTarget(0))
    assertVarState("y" ver 4, blockFail2.getLoadTarget(1))
    assertVarState("x" ver 5, condVarOf(blockFail2))

    val returnBlock = blockFail2.successors[1]
    val retVal = getLoadTarget((returnBlock.terminator as? ImpossibleJump)?.returned, 0)
    assertVarState("x" ver 6, retVal)
  }
}
