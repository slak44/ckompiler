export enum RenamingStep {
  EACH_BB_PREORDER = 1,
  EACH_INSTR,
  CHECK_DEFINED,
  INSTR_REPLACE_DEF,
  CHECK_USED,
  INSTR_REPLACE_USE,
  EACH_SUCC_PHI,
  SUCC_PHI_REPLACE_USE,
  DONE
}

export interface RenamingStepState {
  step: RenamingStep;
  bb?: number;
  i?: number;
  newVersion?: number;
  reachingDefBlock?: number;
  reachingDefIdx?: number;
  succBB?: number;
}

