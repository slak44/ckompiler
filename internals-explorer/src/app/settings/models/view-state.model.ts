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
  publicShareEnabled: boolean;
  owner: string | null;
  name: string;
  sourceCode: string;
  isaType: string;
  activeRoute: string;
  graphViewState: GraphViewState;
  phiInsertionViewState: SteppableGraphViewState;
  variableRenameViewState: SteppableGraphViewState;
}

export type NullableValues<T> = {  [P in keyof T]: T[P] | null };

export type ViewStateMetadataKeys = 'id' | 'name' | 'owner' | 'createdAt' | 'publicShareEnabled';
export type ViewStateMetadata = Pick<ViewState, ViewStateMetadataKeys>;
export type ViewStateNonMetadata = Omit<ViewState, ViewStateMetadataKeys>;
export type ViewStateNonMetadataDelta =
  Omit<ViewState, ViewStateMetadataKeys | 'sourceCode'> & NullableValues<Pick<ViewState, 'sourceCode'>>;

export interface ViewStateListing {
  id: string | null;
  name: string;
  createdAt: string;
  publicShareEnabled: boolean;
}

export function wipeMetadataFromState(state: ViewState | ViewStateNonMetadata): ViewState {
  return { ...state, id: null, name: '', owner: null, createdAt: null, publicShareEnabled: false };
}

export function extractMetadataFromState(state: ViewState): ViewStateMetadata {
  return {
    id: state.id,
    name: state.name,
    owner: state.owner,
    createdAt: state.createdAt,
    publicShareEnabled: state.publicShareEnabled,
  };
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
