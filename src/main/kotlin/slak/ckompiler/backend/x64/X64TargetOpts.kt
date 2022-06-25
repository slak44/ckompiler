package slak.ckompiler.backend.x64

import slak.ckompiler.DebugHandler
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.backend.TargetOptions
import kotlin.js.JsExport

@JsExport
class X64TargetOpts(
    generic: TargetOptions,
    targetOptions: List<String>,
    debugHandler: IDebugHandler,
) : TargetOptions by generic, IDebugHandler by debugHandler {
  val useRedZone: Boolean

  // FIXME: make this configurable in options
  val featureSet: X64SupportedFeatures = X64SupportedFeatures.SSE2

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
