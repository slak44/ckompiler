package slak.ckompiler.backend.dto

import slak.ckompiler.backend.entities.GraphViewState
import slak.ckompiler.backend.entities.SteppableGraphViewState
import slak.ckompiler.backend.entities.ViewState
import slak.ckompiler.backend.entities.ZoomTransform
import java.time.Instant

data class GraphViewStateDto(
    val isUiHidden: Boolean,
    val isSpillOnly: Boolean,
    val targetFunction: String,
    val printingType: String,
    val transform: ZoomTransform?,
    val selectedNodeId: Int?,
) {
  constructor(graphViewState: GraphViewState) : this(
      graphViewState.isUiHidden,
      graphViewState.isSpillOnly,
      graphViewState.targetFunction,
      graphViewState.printingType,
      graphViewState.transform,
      graphViewState.selectedNodeId,
  )
}

data class SteppableGraphViewStateDto(
    val targetVariable: Int?,
    val transform: ZoomTransform?,
    val selectedNodeId: Int?,
    val currentStep: Int,
) {
  constructor(graphViewState: SteppableGraphViewState) : this(
      graphViewState.targetVariable,
      graphViewState.transform,
      graphViewState.selectedNodeId,
      graphViewState.currentStep
  )
}

data class ViewStateNonMetadataDeltaDto(
    val sourceCode: String?,
    val isaType: String,
    val activeRoute: String,
    val graphViewState: GraphViewStateDto,
    val phiInsertionViewState: SteppableGraphViewStateDto,
    val variableRenameViewState: SteppableGraphViewStateDto,
)

data class ViewStateDto(
    val id: String?,
    val createdAt: Instant?,
    val publicShareEnabled: Boolean,
    var owner: String?,
    val name: String,
    val sourceCode: String,
    val isaType: String,
    val activeRoute: String,
    val graphViewState: GraphViewStateDto,
    val phiInsertionViewState: SteppableGraphViewStateDto,
    val variableRenameViewState: SteppableGraphViewStateDto,
) {
  constructor(viewState: ViewState) : this(
      viewState.id.toString(),
      viewState.createdAt,
      viewState.publicShareEnabled,
      viewState.owner,
      viewState.name,
      viewState.sourceCode,
      viewState.isaType,
      viewState.activeRoute,
      GraphViewStateDto(viewState.graphViewState),
      SteppableGraphViewStateDto(viewState.phiInsertionViewState),
      SteppableGraphViewStateDto(viewState.variableRenameViewState),
  )
}
