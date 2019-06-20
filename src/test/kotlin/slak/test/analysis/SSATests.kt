package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.CondJump
import slak.ckompiler.analysis.ImpossibleJump
import slak.ckompiler.analysis.PhiFunction
import slak.ckompiler.parser.BinaryExpression
import slak.ckompiler.parser.TypedIdentifier
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
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 2`() {
    val cfg = prepareCFG(resource("ssa/domsTest2.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 3`() {
    val cfg = prepareCFG(resource("ssa/domsTest3.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `SSA Conversion Doesn't Fail 4`() {
    val cfg = prepareCFG(resource("ssa/domsTest4.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.data.isNotEmpty())
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
      println("${key.variable} (${key.id}) defined in \n\t${value.joinToString("\n\t")}")
    }
    assertEquals(3, cfg.definitions.size)
    val e = cfg.definitions.entries.toList()
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
    fun List<PhiFunction>.x() = firstOrNull { it.target.name == "x" }
    for (i in listOf(1, 2, 9)) assertNotNull(phis(i).x())
    for (i in listOf(0, 3, 4)) assertNull(phis(i).x())
  }

  @Test
  fun `Variable Renaming`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source)

    fun condVarOf(b: BasicBlock) =
        ((b.terminator as? CondJump)?.cond as? BinaryExpression)?.lhs as? TypedIdentifier
    fun rhsOf(b: BasicBlock, idx: Int) = (b.data[idx] as? BinaryExpression)?.rhs
    fun rhsVarOf(b: BasicBlock, idx: Int) = rhsOf(b, idx) as? TypedIdentifier
    infix fun String.ver(version: Int) = this to version
    fun assertVarState(expected: Pair<String, Int>, actual: TypedIdentifier?) {
      assertNotNull(actual)
      assertEquals(expected.first, actual.name)
      assertEquals(expected.second, actual.version)
    }

    val firstBlock = cfg.startBlock.successors[0]
    assertVarState("x" ver 1, condVarOf(firstBlock))

    val blockFail1 = firstBlock.successors[1]
    assertVarState("x" ver 1, rhsVarOf(blockFail1, 0))
    assertVarState("y" ver 1, rhsVarOf(blockFail1, 1))
    assertVarState("tmp" ver 2, rhsVarOf(blockFail1, 3))
    assertVarState("x" ver 3, condVarOf(blockFail1))

    val blockFail2 = blockFail1.successors[1]
    val sumAssigned = assertNotNull(rhsOf(blockFail2, 0) as? BinaryExpression)
    assertVarState("x" ver 4, sumAssigned.lhs as? TypedIdentifier)
    assertVarState("y" ver 4, sumAssigned.rhs as? TypedIdentifier)
    assertVarState("x" ver 5, condVarOf(blockFail2))

    val returnBlock = blockFail2.successors[1]
    val retVal = (returnBlock.terminator as? ImpossibleJump)?.returned as? TypedIdentifier
    assertVarState("x" ver 6, retVal)
  }
}