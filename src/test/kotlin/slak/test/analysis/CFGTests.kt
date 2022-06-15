package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.analysis.external.CodePrintingMethods
import slak.ckompiler.analysis.external.GraphvizOptions
import slak.ckompiler.analysis.external.createGraphviz
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val cfg = prepareCFG(resource("cfg/cfgTest.c"), source)
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Graphviz CFG Creation Doesn't Fail`() {
    val text = resource("cfg/cfgTest.c").readText()
    val cfg = prepareCFG(text, source)
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
    val options = GraphvizOptions(reachableOnly = false, print = CodePrintingMethods.SOURCE_SUBSTRING)
    createGraphviz(cfg, text, options)
  }


  @Test
  fun `Reusing CFG For Graphviz Doesn't Fail`() {
    val text = resource("cfg/cfgTest.c").readText()
    val cfg = prepareCFG(text, source)
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
    createGraphviz(cfg, text, GraphvizOptions(reachableOnly = true, print = CodePrintingMethods.IR_TO_STRING))
    createGraphviz(cfg, text, GraphvizOptions(reachableOnly = true, print = CodePrintingMethods.ASM_TO_STRING))
  }

  @Test
  fun `Break And Continue`() {
    val cfg = prepareCFG(resource("loops/controlKeywordsTest.c"), source)
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `While Loop`() {
    val cfg = prepareCFG(resource("loops/whileLoopTest.c"), source)
    assert(cfg.startBlock.src.isNotEmpty())
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
    assert(cfg.startBlock.src.isNotEmpty())
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
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Early Return In Function`() {
    val cfg = prepareCFG(resource("cfg/earlyReturnTest.c"), source)
    assert(cfg.startBlock.src.isNotEmpty())
    assert(cfg.startBlock.isTerminated())
  }

  @Test
  fun `Pre-order Traversal Of Dominator Tree Is Correct For Diamond Graph`() {
    val cfg = prepareCFG(resource("ssa/trivialDiamondGraphTest.c"), source)
    val sequence = createDomTreePreOrderNodes(cfg.doms, cfg.startBlock, cfg.nodes)
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
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source)
    val sequence = createDomTreePreOrderNodes(cfg.doms, cfg.startBlock, cfg.nodes)
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

  @Test
  fun `Pre-order Traversal Of Dominator Tree Is Correct For Switch Graph`() {
    val cfg = prepareCFG(resource("cfg/switch.c"), source)
    val sequence = createDomTreePreOrderNodes(cfg.doms, cfg.startBlock, cfg.nodes)
    val l = sequence.toList()
    assertEquals(cfg.nodes.size, l.size)
    assertEquals(cfg.startBlock, l[0])
    val ret = l.first { it.terminator is ImpossibleJump }
    assertEquals(cfg.nodes.first { it.terminator is ImpossibleJump }, ret)
    assertEquals(
        cfg.startBlock.successors.sortedBy { it.postOrderId },
        (l - cfg.startBlock - ret).sortedBy { it.postOrderId }
    )
  }

  @Test
  fun `Unterminated Blocks In Function`() {
    val code = "int f() {}"
    val p = prepareCode(code, source)
    val cfg = CFG(p.root.decls.firstFun(), MachineTargetData.x64, source, code)
    cfg.assertDiags(DiagnosticId.CONTROL_END_OF_NON_VOID)
  }

  @Test
  fun `Unterminated Block In Main Is OK`() {
    val cfg = prepareCFG(resource("e2e/emptyMain.c"), source)
    cfg.assertNoDiagnostics()
  }

  @Test
  fun `Switch And SelectJumps`() {
    val cfg = prepareCFG(resource("cfg/switch.c"), source)
    cfg.assertNoDiagnostics()
    assertEquals(5, cfg.nodes.size)
    val ret = cfg.nodes.first { it.terminator is ImpossibleJump }
    assertTrue(ret !in cfg.startBlock.successors)
    val sel = cfg.startBlock.terminator
    assertTrue(sel is SelectJump)
    assertEquals(listOf(ret), sel.default.successors)
    assertEquals(2, sel.options.size)
    val option2 = sel.options.entries.firstOrNull { it.value.successors == listOf(ret) }
    assertNotNull(option2)
    val option1 = (sel.options - option2.key).values.firstOrNull()
    assertNotNull(option1)
    assertEquals(listOf(option2.value), option1.successors)
  }

  @Test
  fun `Postfix In Terminator Conditional Has Correct IR Order`() {
    val cfg = prepareCFG(resource("cfg/postfixInConditional.c"), source)
    val cond = checkNotNull(cfg.startBlock.terminator as? CondJump)
    assert(cond.cond.last() is IntCmp)
  }

  @Test
  fun `Conditional IR Always Ends In Comparison`() {
    val cfg = prepareCFG(resource("cfg/ifWithVariableCond.c"), source)
    val term = cfg.startBlock.terminator
    check(term is CondJump)
    val cmp = term.cond.last()
    check(cmp is IntCmp)
    assert(cmp.lhs !is ConstantValue)
    assert(cmp.rhs is IntConstant)
  }
}
