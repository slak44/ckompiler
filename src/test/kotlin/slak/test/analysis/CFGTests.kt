package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.*
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val cfg = prepareCFG(resource("cfgTest.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 2`() {
    val cfg = prepareCFG(resource("domsTest.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 3`() {
    val cfg = prepareCFG(resource("domsTest2.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 4`() {
    val cfg = prepareCFG(resource("domsTest3.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `CFG Creation Doesn't Fail 5`() {
    val cfg = prepareCFG(resource("domsTest4.c"), source)
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Graphviz CFG Creation Doesn't Fail`() {
    val text = resource("cfgTest.c").readText()
    val cfg = prepareCFG(text, source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
    createGraphviz(cfg, text, reachableOnly = false, useToString = false)
  }

  @Test
  fun `Diamond Graph From If-Else Has Correct Dominance Frontier`() {
    val cfg = prepareCFG(resource("trivialDiamondGraphTest.c"), source)
    assert(cfg.startBlock.dominanceFrontier.isEmpty())
    val t = cfg.startBlock.terminator as CondJump
    val ret = cfg.nodes.first { it.terminator is ImpossibleJump }
    assertEquals(setOf(ret), t.target.dominanceFrontier)
    assertEquals(setOf(ret), t.other.dominanceFrontier)
  }

  @Test
  fun `Labels And Goto`() {
    val cfg = prepareCFG(resource("gotoTest.c"), source)
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Break And Continue`() {
    val cfg = prepareCFG(resource("controlKeywordsTest.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `While Loop`() {
    val cfg = prepareCFG(resource("whileLoopTest.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    // Start block jumps to loop header
    assert(cfg.startBlock.terminator is UncondJump)
    val loopHeader = cfg.startBlock.terminator.successors[0]
    // Loop header conditionally goes in loop block or exits
    assert(loopHeader.terminator is CondJump)
    val condJump = loopHeader.terminator as CondJump
    // Loop block unconditionally jumps back to header
    assertEquals(loopHeader, (condJump.target.terminator as? UncondJump)?.target)
    assertNotEquals(loopHeader, (condJump.other.terminator as? UncondJump)?.target)
  }

  @Test
  fun `For Loop`() {
    val cfg = prepareCFG(resource("forLoopTest.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Early Return In Function`() {
    val cfg = prepareCFG(resource("earlyReturnTest.c"), source)
    assert(cfg.startBlock.data.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Correct Definition Tracking Test`() {
    val cfg = prepareCFG(resource("phiTest.c"), source)
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
    val cfg = prepareCFG(resource("phiTest.c"), source)
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
