package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.Return
import slak.ckompiler.analysis.createGraphFor
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.parser.ExternalDeclaration
import slak.ckompiler.parser.FunctionDefinition
import slak.test.assertNoDiagnostics
import slak.test.prepareCode
import slak.test.resource
import slak.test.source

private fun List<ExternalDeclaration>.firstFun(): FunctionDefinition =
    first { it is FunctionDefinition } as FunctionDefinition

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val p = prepareCode(resource("cfgTest.c").readText(), source)
    p.assertNoDiagnostics()
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.isTerminated())
  }

  @Test
  fun `Graphviz CFG Creation Doesn't Fail`() {
    val text = resource("cfgTest.c").readText()
    val p = prepareCode(text, source)
    p.assertNoDiagnostics()
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.isTerminated())
    createGraphviz(startBlock, text)
  }

  @Test
  fun `Dead Code After Return`() {
    val p = prepareCode("""
      int main() {
        int x = 1;
        return x;
        int y = 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val startBlock = createGraphFor(p.root.decls.firstFun())
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.terminator is Return)
    val ret = startBlock.terminator as Return
    assert(ret.deadCode != null)
    assert(ret.deadCode!!.data.isNotEmpty())
  }
}
