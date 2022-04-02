import { Injectable, NgZone } from '@angular/core';
import { combineLatest, filter, map, Observable, Subject, takeUntil, tap } from 'rxjs';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { slak } from '@ckompiler/ckompiler';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { groupedDebounceByFrame } from '@cki-utils/async-timeout';
import { PhiInsertionStepState } from '../models/phi-insertion-steps.model';
import { CompilationInstance, compileCode } from '@cki-graph-view/compilation-instance';
import { TargetVariableState } from '@cki-graph-view/target-variable-state';
import { AlgorithmStepService } from '../../algorithm-stepper/services/algorithm-step.service';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import generatePhiSteps = slak.ckompiler.analysis.external.generatePhiSteps;
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;

@Injectable()
export class PhiInsertionStateService extends SubscriptionDestroy {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    tap(() => this.algorithmStepService.reset()),
    compileCode(true),
  );

  public readonly compilationInstance: CompilationInstance = new CompilationInstance(this.compileResult$);

  public readonly varState: TargetVariableState = new TargetVariableState(this.compilationInstance);

  private readonly reLayoutSubject: Subject<number> = new Subject<number>();

  private readonly allInsertionSteps$: Observable<PhiInsertionStepState[]> = combineLatest([
    this.compilationInstance.cfg$,
    this.varState.targetVariable$,
  ]).pipe(
    filter(([cfg, variable]) => phiEligibleVariables(cfg).includes(variable)),
    map(([cfg, variable]) => JSON.parse(generatePhiSteps(cfg, variable)) as PhiInsertionStepState[]),
  );

  public readonly insertionStepCount$: Observable<number> = this.allInsertionSteps$.pipe(
    map(steps => steps.length),
  );

  public readonly currentStepState$: Observable<PhiInsertionStepState> = this.allInsertionSteps$.pipe(
    this.algorithmStepService.mapToCurrentStep()
  );

  constructor(
    private readonly algorithmStepService: AlgorithmStepService,
    private readonly compileService: CompileService,
    private readonly replaceNodeContentsHook: ReplaceNodeContentsHook,
    private readonly ngZone: NgZone,
  ) {
    super();

    this.reLayoutSubject.pipe(
      groupedDebounceByFrame(this.ngZone),
      takeUntil(this.destroy$),
    ).subscribe((nodeId: number) => {
      this.replaceNodeContentsHook.reLayoutNodeFragments(nodeId);
    });
  }

  public triggerReLayout(nodeId: number): void {
    this.reLayoutSubject.next(nodeId);
  }
}
