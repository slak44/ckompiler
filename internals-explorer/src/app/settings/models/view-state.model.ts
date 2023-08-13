export interface ZoomTransformDto {
  k: number;
  x: number;
  y: number;
}

export interface GraphViewState {
  isUiHidden: boolean;
  isSpillOnly: boolean;
  targetFunction: string;
  printingType: string;
  transform: ZoomTransformDto;
  selectedNodeId: number | null;
}

export interface SteppableGraphViewState {
  targetVariable: number | null;
  transform: ZoomTransformDto;
  selectedNodeId: number | null;
  currentStep: number;
}

export interface ViewState {
  id: string | null;
  createdAt: string | null;
  owner: string | null;
  name: string;
  sourceCode: string;
  isaType: string;
  activeRoute: string;
  graphViewState: GraphViewState;
  phiInsertionViewState: SteppableGraphViewState;
  variableRenameViewState: SteppableGraphViewState;
}

export interface ViewStateListing {
  id: string | null;
  name: string;
  createdAt: string;
}

export function hasEqualViewStates(a: ViewState, b: ViewState): boolean {
  return a.id === b.id &&
    a.name === b.name &&
    a.createdAt === b.createdAt &&
    a.owner === b.owner &&
    a.sourceCode === b.sourceCode &&
    a.isaType === b.isaType &&
    a.activeRoute === b.activeRoute &&
    a.graphViewState.isUiHidden === b.graphViewState.isUiHidden &&
    a.graphViewState.isSpillOnly === b.graphViewState.isSpillOnly &&
    a.graphViewState.selectedNodeId === b.graphViewState.selectedNodeId &&
    a.graphViewState.printingType === b.graphViewState.printingType &&
    a.graphViewState.targetFunction === b.graphViewState.targetFunction &&
    a.graphViewState.transform.k === b.graphViewState.transform.k &&
    a.graphViewState.transform.x === b.graphViewState.transform.x &&
    a.graphViewState.transform.y === b.graphViewState.transform.y &&
    a.phiInsertionViewState.transform.k === b.phiInsertionViewState.transform.k &&
    a.phiInsertionViewState.transform.x === b.phiInsertionViewState.transform.x &&
    a.phiInsertionViewState.transform.y === b.phiInsertionViewState.transform.y &&
    a.phiInsertionViewState.targetVariable === b.phiInsertionViewState.targetVariable &&
    a.phiInsertionViewState.selectedNodeId === b.phiInsertionViewState.selectedNodeId &&
    a.phiInsertionViewState.currentStep === b.phiInsertionViewState.currentStep &&
    a.variableRenameViewState.transform.k === b.variableRenameViewState.transform.k &&
    a.variableRenameViewState.transform.x === b.variableRenameViewState.transform.x &&
    a.variableRenameViewState.transform.y === b.variableRenameViewState.transform.y &&
    a.variableRenameViewState.targetVariable === b.variableRenameViewState.targetVariable &&
    a.variableRenameViewState.selectedNodeId === b.variableRenameViewState.selectedNodeId &&
    a.variableRenameViewState.currentStep === b.variableRenameViewState.currentStep;
}
