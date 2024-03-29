export enum PhiInsertionStep {
  PREPARE = 1,
  WHILE_LOOP,
  PICK_X_FROM_W,
  ITERATE_DF,
  CHECK_PROCESSED,
  INSERT_PHI,
  CHECK_DEFS,
  ADD_TO_W,
  DONE
}

export interface PhiInsertionStepState {
  step: PhiInsertionStep;
  blockX?: number;
  blockY?: number;
  highlightedPhiPaths?: number[][];
  f: number[];
  w: number[];
}
