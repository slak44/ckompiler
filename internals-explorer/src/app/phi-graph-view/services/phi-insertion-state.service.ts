import { Injectable, NgZone } from '@angular/core';
import {
  BehaviorSubject,
  combineLatest,
  distinctUntilChanged,
  filter,
  map,
  Observable,
  shareReplay,
  Subject,
  takeUntil,
  tap,
} from 'rxjs';
import { compileCode, CompileService } from '@cki-graph-view/services/compile.service';
import { slak } from '@ckompiler/ckompiler';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { groupedDebounceByFrame } from '@cki-utils/async-timeout';
import { PhiInsertionStepState } from '../models/phi-insertion-steps.model';
import { clamp } from 'lodash-es';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import Variable = slak.ckompiler.analysis.Variable;
import CFG = slak.ckompiler.analysis.CFG;
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;
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

  public readonly cfg$: Observable<CFG> = this.compileResult$.pipe(
    filter(compileResult => !!compileResult.cfgs),
    map(compileResult => compileResult.cfgs!.find(cfg => cfg.f.name === 'main')),
    filter((cfg): cfg is CFG => !!cfg),
  );

  public readonly variables$: Observable<Variable[]> = this.cfg$.pipe(
    map(cfg => phiEligibleVariables(cfg)),
  );

  private readonly targetVariableIdSubject: Subject<number> = new Subject<number>();

  public readonly targetVariable$: Observable<Variable> = combineLatest([
    this.targetVariableIdSubject,
    this.cfg$,
  ]).pipe(
    map(([identityId, cfg]) => phiEligibleVariables(cfg).find(variable => variable.identityId === identityId)!),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  private readonly phiInsertionPhaseSubject: BehaviorSubject<PhiInsertionPhase> =
    new BehaviorSubject<PhiInsertionPhase>(PhiInsertionPhase.CONFIGURE);

  public readonly phiInsertionPhase$: Observable<PhiInsertionPhase> = this.phiInsertionPhaseSubject;

  private readonly reLayoutSubject: Subject<number> = new Subject<number>();

  private readonly allInsertionSteps$: Observable<PhiInsertionStepState[]> = combineLatest([
    this.cfg$,
    this.targetVariable$,
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

  public selectedVariableChanged(variableIdentityId: number): void {
    this.targetVariableIdSubject.next(variableIdentityId);
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
