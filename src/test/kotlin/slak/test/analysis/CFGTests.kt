package slak.test.analysis

import org.junit.Test
import slak.ckompiler.analysis.createGraphFor
import slak.ckompiler.parser.FunctionDefinition
import slak.test.assertNoDiagnostics
import slak.test.prepareCode
import slak.test.resource
import slak.test.source
import java.io.File

class CFGTests {
  @Test
  fun `CFG Creation Doesn't Fail`() {
    val p = prepareCode(resource("cfgTest.c").readText(), source)
    p.assertNoDiagnostics()
    val startBlock = createGraphFor(p.root.decls.mapNotNull { d -> d as? FunctionDefinition }[0])
    assert(startBlock.data.isNotEmpty())
    assert(startBlock.terminator != null)
  }
}
