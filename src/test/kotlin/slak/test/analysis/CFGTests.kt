package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.analysis.*
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val cfg = prepareCFG(resource("cfgTest.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Graphviz CFG Creation Doesn't Fail`() {
    val text = resource("cfgTest.c").readText()
    val cfg = prepareCFG(text, source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
    createGraphviz(cfg, text, reachableOnly = false, print = CodePrintingMethods.SOURCE_SUBSTRING)
  }

  @Test
  fun `Break And Continue`() {
    val cfg = prepareCFG(resource("loops/controlKeywordsTest.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `While Loop`() {
    val cfg = prepareCFG(resource("loops/whileLoopTest.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
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
  fun `Do While Loop`() {
    val cfg = prepareCFG(resource("loops/doWhileLoopTest.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    // Start block jumps to loop block
    assert(cfg.startBlock.terminator is UncondJump)
    val loopBlock = cfg.startBlock.terminator.successors[0]
    // Loop block conditionally goes back in itself or exits
    assert(loopBlock.terminator is CondJump)
    val condJump = loopBlock.terminator as CondJump
    assertEquals(loopBlock, condJump.target)
    assertNotEquals(loopBlock, condJump.other)
  }

  @Test
  fun `For Loop`() {
    val cfg = prepareCFG(resource("loops/forLoopTest.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Early Return In Function`() {
    val cfg = prepareCFG(resource("earlyReturnTest.c"), source)
    assert(cfg.startBlock.irContext.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Pre-order Traversal Of Dominator Tree Is Correct For Diamond Graph`() {
    val cfg = prepareCFG(resource("ssa/trivialDiamondGraphTest.c"), source, convertToSSA = false)
    val sequence = createDomTreePreOrderSequence(cfg.doms, cfg.startBlock, cfg.nodes)
    val correctOrder = listOf(
        cfg.startBlock,
        cfg.startBlock.successors[0],
        cfg.startBlock.successors[1],
        cfg.startBlock.successors[0].successors[0]
    )
    assertEquals(correctOrder, sequence.toList())
  }

  @Test
  fun `Pre-order Traversal Of Dominator Tree Is Correct For Phi Test Graph`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source, convertToSSA = false)
    val sequence = createDomTreePreOrderSequence(cfg.doms, cfg.startBlock, cfg.nodes)
    val correctOrder = listOf(
        cfg.startBlock,
        cfg.startBlock.successors[0],
        cfg.startBlock.successors[0].successors[0],
        cfg.startBlock.successors[0].successors[1],
        cfg.startBlock.successors[0].successors[0].successors[0],
        cfg.startBlock.successors[0].successors[0].successors[0].successors[1]
    )
    assertEquals(correctOrder, sequence.toList())
  }
}
