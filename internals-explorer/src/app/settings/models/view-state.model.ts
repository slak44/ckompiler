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

export interface ViewState {
  id: string | null;
  createdAt: string | null;
  owner: string | null;
  name: string;
  sourceCode: string;
  isaType: string;
  activeRoute: string;
  graphViewState: GraphViewState;
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
    a.graphViewState.transform.y === b.graphViewState.transform.y;
}
