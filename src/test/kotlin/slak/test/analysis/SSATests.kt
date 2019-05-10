package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.CondJump
import slak.ckompiler.analysis.ImpossibleJump
import slak.ckompiler.analysis.PhiFunction
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
      println("${key.first} (${key.second}) defined in \n\t${value.joinToString("\n\t")}")
    }
    assertEquals(3, cfg.definitions.size)
    val e = cfg.definitions.entries.toList()
    val id = cfg.startBlock.nodeId
    assertEquals(e[0].value.map { it.nodeId }, listOf(0, 3, 4, 9).map { it + id })
    assertEquals(e[1].value.map { it.nodeId }, listOf(0, 3, 4).map { it + id })
    assertEquals(e[2].value.map { it.nodeId }, listOf(0, 4).map { it + id })
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
}
