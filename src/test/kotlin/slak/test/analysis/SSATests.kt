package slak.test.analysis

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.analysis.*
import slak.ckompiler.analysis.external.irToString
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SSATests {
  private fun CFG.assertIsSSA() {
    val defined = mutableSetOf<Variable>()
    for (block in domTreePreorder) {
      for (instr in block.instructions) {
        if (instr is MoveInstr && instr.result is Variable) {
          val variable = instr.result as Variable
          assert(variable !in defined)
          defined += variable
        }
      }
    }
  }

  @Test
  fun `SSA Conversion Doesn't Fail 1`() {
    val cfg = prepareCFG(resource("ssa/domsTest.c"), source).create()
    cfg.assertIsSSA()
  }

  @Test
  fun `SSA Conversion Doesn't Fail 2`() {
    val cfg = prepareCFG(resource("ssa/domsTest2.c"), source).create()
    cfg.assertIsSSA()
  }

  @Test
  fun `SSA Conversion Doesn't Fail 3`() {
    val cfg = prepareCFG(resource("ssa/domsTest3.c"), source).create()
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    cfg.assertIsSSA()
  }

  @Test
  fun `SSA Conversion Doesn't Fail 4`() {
    val cfg = prepareCFG(resource("ssa/domsTest4.c"), source).create()
    cfg.nodes.forEach { println(it.toString() + " frontier:\t" + it.dominanceFrontier) }
    cfg.assertIsSSA()
  }

  @Test
  fun `Diamond Graph From If-Else Has Correct Dominance Frontier`() {
    val cfg = prepareCFG(resource("ssa/trivialDiamondGraphTest.c"), source).create()
    cfg.assertIsSSA()
    assert(cfg.startBlock.dominanceFrontier.isEmpty())
    val t = cfg.startBlock.terminator as CondJump
    val ret = cfg.nodes.first { it.terminator is ImpossibleJump }
    assertEquals(setOf(ret), t.target.dominanceFrontier)
    assertEquals(setOf(ret), t.other.dominanceFrontier)
  }

  @Test
  fun `Correct Definition Tracking Test`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source).create()
    cfg.assertIsSSA()
    for ((key, value) in cfg.exprDefinitions) {
      println("$key (${key.identityId}) defined in \n\t${value.joinToString("\n\t")}")
    }
    val realDefs = cfg.exprDefinitions
    assertEquals(3, realDefs.size)
    val e = realDefs.values.toList()
    assertEquals(e[0].map { it.postOrderId }, listOf(5, 2, 3, 1))
    assertEquals(e[1].map { it.postOrderId }, listOf(5, 2, 3))
    assertEquals(e[2].map { it.postOrderId }, listOf(3))
  }

  @Test
  fun `Phi Insertion`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source).create()
    cfg.assertIsSSA()
    for (node in cfg.nodes) {
      println("$node φ-functions: \n\t${node.phi.joinToString("\n\t")}")
    }
    fun phis(id: Int) = cfg.nodes.first { it.postOrderId == id }.phi
    fun Iterable<PhiInstruction>.x() = firstOrNull { it.variable.name == "x" }
    for (i in listOf(4, 0, 1)) assertNotNull(phis(i).x())
    for (i in listOf(5, 2, 3)) assertNull(phis(i).x())
  }

  @Test
  fun `Variable Renaming`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source).create()
    cfg.assertIsSSA()

    fun getStoreValue(list: List<IRInstruction>?, at: Int) =
        (list?.get(at) as? MoveInstr)?.value as? Variable

    fun BasicBlock.getStoreValue(at: Int) = getStoreValue(ir, at)

    fun condVarOf(b: BasicBlock) =
        ((b.terminator as? CondJump)?.cond?.get(0) as? IntCmp)?.lhs as? Variable

    infix fun String.ver(version: Int) = this to version

    fun assertVarState(expected: Pair<String, Int>, actual: Variable?) {
      assertNotNull(actual)
      assertEquals(expected.first, actual.name)
      assertEquals(expected.second, actual.version)
    }

    val firstBlock = cfg.startBlock.successors[0]
    assertVarState("x" ver 1, condVarOf(firstBlock))

    val blockFail1 = firstBlock.successors[1]
    assertVarState("x" ver 1, blockFail1.getStoreValue(0))
    assertVarState("y" ver 1, blockFail1.getStoreValue(1))
    assertVarState("tmp" ver 2, blockFail1.getStoreValue(2))
    assertVarState("x" ver 3, condVarOf(blockFail1))

    val blockFail2 = blockFail1.successors[1]
    assertVarState("x" ver 4, (blockFail2.ir[0] as? IntBinary)?.lhs as? Variable)
    assertVarState("y" ver 4, (blockFail2.ir[0] as? IntBinary)?.rhs as? Variable)
    assertVarState("x" ver 5, condVarOf(blockFail2))

    val returnBlockImpJmp = blockFail2.successors[1].terminator as? ImpossibleJump
    val retVal = (returnBlockImpJmp?.returned?.get(0) as? MoveInstr)?.value as? Variable
    assertVarState("x" ver 6, retVal)
  }

  private fun printDefUse(cfg: CFG) {
    for (block in cfg.domTreePreorder) {
      println("$block:")
      println(block.irToString())
      println()
    }
    for ((variable, uses) in cfg.defUseChains) {
      println("uses of $variable:")
      for ((block, label) in uses) {
        println("\t$block, at label index $label")
      }
    }
  }

  private fun CFG.useChainOf(name: String, version: Int): List<Label> {
    return defUseChains.keys
        .first { it.tid.name == name && it.version == version }
        .let { defUseChains.getValue(it) }
  }

  @Test
  fun `Def-Use Chains On Program With One Basic Block`() {
    val cfg = prepareCFG(resource("ssa/sameDefMultipleUses.c"), source).create()
    cfg.assertIsSSA()
    printDefUse(cfg)
    with(cfg) {
      assertEquals(listOf(2, 4, 6).map { startBlock.nodeId to it }, useChainOf("a", 1))
      assertEquals(listOf(startBlock.nodeId to 2), useChainOf("b", 1))
      assertEquals(listOf(startBlock.nodeId to 4), useChainOf("b", 2))
      assertEquals(listOf(startBlock.nodeId to 6), useChainOf("b", 3))
    }
  }

  @Test
  fun `Def-Use Chains With For Loop`() {
    val cfg = prepareCFG(resource("loops/forLoopTest.c"), source).create()
    cfg.assertIsSSA()
    printDefUse(cfg)
    with(cfg) {
      val loopHeader = startBlock.successors[0]
      val loopBody = loopHeader.successors[0]
      val returnBlock = loopHeader.successors[1]
      assertEquals(listOf(loopHeader.nodeId to DEFINED_IN_PHI), useChainOf("x", 1))
      assertEquals(listOf(loopHeader.nodeId to DEFINED_IN_PHI), useChainOf("i", 1))
      assertEquals(listOf(loopHeader.nodeId to 0, loopBody.nodeId to 2, loopBody.nodeId to 3), useChainOf("i", 2))
      assertEquals(listOf(loopBody.nodeId to 0, returnBlock.nodeId to 0), useChainOf("x", 2))
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["ssa/domsTest.c", "ssa/domsTest2.c", "ssa/domsTest3.c"])
  fun `Iterated Dominance Frontier Works`(fileName: String) {
    val cfg = prepareCFG(resource(fileName), source).create()
    cfg.assertIsSSA()
    val block1 = cfg.startBlock.successors[0]
    val variable = cfg.definitions.keys.first { it.name == "x" && it.version == 2 }
    val gen = X64Generator(cfg, X64Target())
    with(gen.graph) {
      val iteratedFront = iteratedDominanceFrontier(listOf(block1.nodeId), setOf(variable.identityId))
      val actualIterated = cfg.nodes.filter { it.phi.any { (variable1) -> variable1.identityId == variable.identityId } }.toSet()
      assertEquals(actualIterated.map { it.nodeId }.toSet(), iteratedFront)
    }
  }

  @Test
  fun `Pre-order Traversal Of Dominator Tree Is Correct For Diamond Graph`() {
    val cfg = prepareCFG(resource("ssa/trivialDiamondGraphTest.c"), source).create()
    cfg.assertIsSSA()
    val gen = X64Generator(cfg, X64Target())
    val sequence = createDomTreePreOrderNodes(cfg.doms, cfg.startBlock, cfg.nodes)
    val instrSequence = gen.graph.domTreePreorder.asSequence()
    val correctOrder = listOf(
        cfg.startBlock,
        cfg.startBlock.successors[0],
        cfg.startBlock.successors[1],
        cfg.startBlock.successors[0].successors[0]
    )
    val correctForInstr = correctOrder.map { it.nodeId }
    assertEquals(correctOrder, sequence.toList())
    assertEquals(correctForInstr, instrSequence.toList())
  }

  @Test
  fun `Pre-order Traversal Of Dominator Tree Is Correct For 3-Node Graph`() {
    val cfg = prepareCFG(resource("codegen/interBlockInterference.c"), source).create()
    cfg.assertIsSSA()
    val gen = X64Generator(cfg, X64Target())
    val sequence = createDomTreePreOrderNodes(cfg.doms, cfg.startBlock, cfg.nodes)
    val instrSequence = gen.graph.domTreePreorder.asSequence()
    val correctOrder = listOf(
        cfg.startBlock,
        cfg.startBlock.successors[0],
        cfg.startBlock.successors[0].successors[0]
    )
    val correctForInstr = correctOrder.map { it.nodeId }
    println(correctOrder)
    println(correctForInstr)
    assertEquals(correctOrder, sequence.toList())
    assertEquals(correctForInstr, instrSequence.toList())
  }

  @Test
  fun `Reverse Post Order For InstructionGraph Is Correct`() {
    val cfg = prepareCFG(resource("ssa/domsTest.c"), source).create()
    cfg.assertIsSSA()
    val gen = X64Generator(cfg, X64Target())
    val loopedBlocks = gen.graph.blocks.filter {
      gen.graph.successors(it).intersect(gen.graph.predecessors(it)).isNotEmpty()
    }
    assertEquals(2, loopedBlocks.size)
    val revPostOrder = gen.graph.postOrder().reversed()
    assertEquals(5, revPostOrder.size)
    assert(loopedBlocks.intersect(revPostOrder.takeLast(2)).size == 2)
    assert(gen.graph.successors(gen.graph.startId).map { it.id }.intersect(revPostOrder.drop(1).take(2)).size == 2)
    assertEquals(gen.graph.startId, revPostOrder[0])
  }
}
