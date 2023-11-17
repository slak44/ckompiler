package slak.test.analysis

import org.junit.jupiter.api.Test
import slak.ckompiler.analysis.external.generateRenameSteps
import slak.test.prepareCFG
import slak.test.resource
import slak.test.source

class ExportTests {
  @Test
  fun `Generate Renaming States Works`() {
    val cfg = prepareCFG(resource("ssa/phiTest.c"), source).create()
    for (variable in cfg.exprDefinitions.keys) {
      generateRenameSteps(cfg, variable)
    }
  }
}
