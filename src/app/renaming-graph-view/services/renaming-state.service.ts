import { Injectable } from '@angular/core';
import { combineLatest, map, Observable } from 'rxjs';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { generateRenameSteps, JSCompileResult, RenameReplacements } from '@ckompiler/ckompiler';
import { CompilationInstance, compileCode } from '@cki-graph-view/compilation-instance';
import { TargetVariableState } from '@cki-graph-view/target-variable-state';
import { RenamingStepState } from '../models/renaming-step.model';
import { AlgorithmStepService } from '../../algorithm-stepper/services/algorithm-step.service';
import { variableRenameVariableId } from '@cki-settings';

@Injectable()
export class RenamingStateService {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    compileCode(this.compileService.isaType$),
  );

  public readonly compilationInstance: CompilationInstance = new CompilationInstance(this.compileResult$);

  public readonly varState: TargetVariableState =
    new TargetVariableState(this.compilationInstance, variableRenameVariableId);

  public readonly renameReplacements$: Observable<RenameReplacements> = combineLatest([
    this.compilationInstance.cfg$,
    this.varState.targetVariable$,
  ]).pipe(
    map(([cfg, variable]) => generateRenameSteps(cfg, variable)),
  );

  private readonly allInsertionSteps$: Observable<RenamingStepState[]> = this.renameReplacements$.pipe(
    map(renameReplacements => JSON.parse(renameReplacements.serializedRenameSteps) as RenamingStepState[]),
  );

  public readonly stepCount$: Observable<number> = this.allInsertionSteps$.pipe(
    map(steps => steps.length),
  );

  public readonly currentStepState$: Observable<RenamingStepState> = this.allInsertionSteps$.pipe(
    this.algorithmStepService.mapToCurrentStep(),
  );

  constructor(
    private readonly compileService: CompileService,
    private readonly algorithmStepService: AlgorithmStepService,
  ) {
  }
}
