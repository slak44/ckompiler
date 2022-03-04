import { Injectable, NgZone } from '@angular/core';
import {
  BehaviorSubject,
  combineLatest,
  distinctUntilChanged,
  map,
  Observable,
  shareReplay,
  Subject,
  takeUntil,
  tap,
} from 'rxjs';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { slak } from '@ckompiler/ckompiler';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { groupedDebounceByFrame } from '@cki-utils/async-timeout';
import { PhiInsertionStepState } from '../models/phi-insertion-steps.model';
import { clamp } from 'lodash-es';
import { CompilationInstance, compileCode } from '@cki-graph-view/compilation-instance';
import { TargetVariableState } from '@cki-graph-view/target-variable-state';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import generatePhiSteps = slak.ckompiler.generatePhiSteps;

export enum PhiInsertionPhase {
  CONFIGURE,
  WORKLOOP,
}

@Injectable()
export class PhiInsertionStateService extends SubscriptionDestroy {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    tap(() => this.phiInsertionPhaseSubject.next(PhiInsertionPhase.CONFIGURE)),
    compileCode(),
  );

  public readonly compilationInstance: CompilationInstance = new CompilationInstance(this.compileResult$);

  public readonly varState: TargetVariableState = new TargetVariableState(this.compilationInstance);

  private readonly phiInsertionPhaseSubject: BehaviorSubject<PhiInsertionPhase> =
    new BehaviorSubject<PhiInsertionPhase>(PhiInsertionPhase.CONFIGURE);

  public readonly phiInsertionPhase$: Observable<PhiInsertionPhase> = this.phiInsertionPhaseSubject;

  private readonly reLayoutSubject: Subject<number> = new Subject<number>();

  private readonly allInsertionSteps$: Observable<PhiInsertionStepState[]> = combineLatest([
    this.compilationInstance.cfg$,
    this.varState.targetVariable$,
  ]).pipe(
    map(([cfg, variable]) => JSON.parse(generatePhiSteps(cfg, variable)) as PhiInsertionStepState[]),
  );

  public readonly insertionStepCount$: Observable<number> = this.allInsertionSteps$.pipe(
    map(steps => steps.length),
  );

  private readonly currentStepSubject: BehaviorSubject<number> = new BehaviorSubject<number>(0);

  public readonly currentStep$: Observable<number> = this.currentStepSubject.pipe(
    distinctUntilChanged(),
  );

  public readonly currentStepState$: Observable<PhiInsertionStepState> = combineLatest([
    this.allInsertionSteps$,
    this.currentStep$
  ]).pipe(
    map(([steps, index]) => {
      const clamped = clamp(index, 0, steps.length - 1);
      if (clamped !== index) {
        this.currentStepSubject.next(clamped);
      }

      return steps[clamped];
    }),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  constructor(
    private compileService: CompileService,
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
    private ngZone: NgZone,
  ) {
    super();

    this.reLayoutSubject.pipe(
      groupedDebounceByFrame(this.ngZone),
      takeUntil(this.destroy$),
    ).subscribe((nodeId: number) => {
      this.replaceNodeContentsHook.reLayoutNodeFragments(nodeId);
    });
  }

  public startInsertion(): void {
    this.phiInsertionPhaseSubject.next(PhiInsertionPhase.WORKLOOP);
    this.currentStepSubject.next(0);
  }

  public reset(): void {
    this.phiInsertionPhaseSubject.next(PhiInsertionPhase.CONFIGURE);
  }

  public triggerReLayout(nodeId: number): void {
    this.reLayoutSubject.next(nodeId);
  }

  public nextStep(): void {
    if (this.phiInsertionPhaseSubject.value !== PhiInsertionPhase.WORKLOOP) {
      return;
    }
    this.currentStepSubject.next(this.currentStepSubject.value + 1);
  }

  public prevStep(): void {
    if (this.phiInsertionPhaseSubject.value !== PhiInsertionPhase.WORKLOOP) {
      return;
    }
    this.currentStepSubject.next(this.currentStepSubject.value - 1);
  }

  public setStep(value: number): void {
    if (this.phiInsertionPhaseSubject.value !== PhiInsertionPhase.WORKLOOP) {
      return;
    }
    this.currentStepSubject.next(value);
  }
}
