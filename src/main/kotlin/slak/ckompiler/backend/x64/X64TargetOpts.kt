package slak.ckompiler.backend.x64

import slak.ckompiler.DebugHandler
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.backend.TargetOptions

class X64TargetOpts(
    generic: TargetOptions,
    targetOptions: List<String>,
    debugHandler: IDebugHandler,
) : TargetOptions by generic, IDebugHandler by debugHandler {
  val useRedZone: Boolean

  init {
    val optsLeft = targetOptions.toMutableSet()
    if ("no-red-zone" in targetOptions) {
      optsLeft -= "no-red-zone"
      useRedZone = false
    } else {
      useRedZone = true
    }
    for (unusedOpt in optsLeft) {
      diagnostic {
        id = DiagnosticId.BAD_CLI_OPTION
        formatArgs("-m$unusedOpt")
      }
    }
  }

  companion object {
    val defaults = X64TargetOpts(TargetOptions.defaults, emptyList(), DebugHandler("", "", ""))
  }
}
