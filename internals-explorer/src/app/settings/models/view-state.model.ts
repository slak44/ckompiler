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
  transform: ZoomTransformDto | null;
  selectedNodeId: number | null;
}

export interface SteppableGraphViewState {
  targetVariable: number | null;
  transform: ZoomTransformDto | null;
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

export function hasEqualGraphViewState(a: GraphViewState, b: GraphViewState): boolean {
  return a.isUiHidden === b.isUiHidden &&
    a.isSpillOnly === b.isSpillOnly &&
    a.selectedNodeId === b.selectedNodeId &&
    a.printingType === b.printingType &&
    a.targetFunction === b.targetFunction &&
    a.transform?.k === b.transform?.k &&
    a.transform?.x === b.transform?.x &&
    a.transform?.y === b.transform?.y;
}

export function hasEqualSteppableGraphViewState(a: SteppableGraphViewState, b: SteppableGraphViewState): boolean {
  return a.transform?.k === b.transform?.k &&
    a.transform?.x === b.transform?.x &&
    a.transform?.y === b.transform?.y &&
    a.targetVariable === b.targetVariable &&
    a.selectedNodeId === b.selectedNodeId &&
    a.currentStep === b.currentStep;
}

export function hasEqualViewStates(a: ViewState, b: ViewState): boolean {
  return a.id === b.id &&
    a.name === b.name &&
    a.createdAt === b.createdAt &&
    a.owner === b.owner &&
    a.sourceCode === b.sourceCode &&
    a.isaType === b.isaType &&
    a.activeRoute === b.activeRoute &&
    hasEqualGraphViewState(a.graphViewState, b.graphViewState) &&
    hasEqualSteppableGraphViewState(a.phiInsertionViewState, b.phiInsertionViewState) &&
    hasEqualSteppableGraphViewState(a.variableRenameViewState, b.variableRenameViewState);
}
