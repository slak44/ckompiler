package slak.ckompiler.analysis.external

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.CFG

@Serializable
private data class CFGExportModel(
    val startBlock: AtomicId,
    val allNodes: Set<BasicBlock>,
)

val json = Json { classDiscriminator = "discriminator" }
fun exportCFG(cfg: CFG): String {
  return json.encodeToString(CFGExportModel(cfg.startBlock.nodeId, cfg.allNodes))
}
