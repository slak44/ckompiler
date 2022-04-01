/**
 * The options in this file are not intended to alter compiler functionality.
 * Instead, they control global debugging options, which cannot be passed cleanly otherwise.
 */

package slak.ckompiler

import kotlin.js.JsExport

/**
 * If false, do not print the version in [slak.ckompiler.analysis.Variable.toString] and [slak.ckompiler.analysis.PhiInstruction.toString].
 */
@JsExport
var printVariableVersions = true
