import { Injectable } from '@angular/core';
import { BehaviorSubject, combineLatest, filter, map, Observable, shareReplay, Subject, takeUntil } from 'rxjs';
import { CompileService, logCompileError } from '@cki-graph-view/services/compile.service';
import { Nullable, slak } from '@ckompiler/ckompiler';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { groupedDebounceByFrame } from '@cki-utils/async-timeout';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import jsCompile = slak.ckompiler.jsCompile;
import Variable = slak.ckompiler.analysis.Variable;
import CFG = slak.ckompiler.analysis.CFG;
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;
import clearAllAtomicCounters = slak.ckompiler.clearAllAtomicCounters;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import definitionsOf = slak.ckompiler.definitionsOf;

export enum PhiInsertionState {
  CONFIGURE,
  WORKLOOP,
  DONE
}

@Injectable()
export class PhiInsertionStateService extends SubscriptionDestroy {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    map(code => {
      try {
        this.phiInsertionStateSubject.next(PhiInsertionState.CONFIGURE);
        clearAllAtomicCounters();
        return jsCompile(code, true);
      } catch (e) {
        logCompileError(e);
        return null;
      }
    }),
    filter((compileResult: Nullable<JSCompileResult>): compileResult is JSCompileResult => !!compileResult),
    shareReplay({ bufferSize: 1, refCount: false }),
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

  private readonly phiInsertionStateSubject: BehaviorSubject<PhiInsertionState> =
    new BehaviorSubject<PhiInsertionState>(PhiInsertionState.CONFIGURE);

  public readonly phiInsertionState$: Observable<PhiInsertionState> = this.phiInsertionStateSubject;

  private readonly reLayoutSubject: Subject<number> = new Subject<number>();

  private readonly initialWorklist$: Observable<BasicBlock[]> = combineLatest([
    this.cfg$,
    this.targetVariable$,
  ]).pipe(
    map(([cfg, targetVariable]) => definitionsOf(targetVariable, cfg)),
  );

  private readonly worklistSubject: BehaviorSubject<BasicBlock[]> = new BehaviorSubject<BasicBlock[]>([]);

  public readonly worklist$: Observable<BasicBlock[]> = this.worklistSubject;

  constructor(
    private compileService: CompileService,
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
  ) {
    super();

    this.reLayoutSubject.pipe(
      groupedDebounceByFrame(),
      takeUntil(this.destroy$),
    ).subscribe((nodeId: number) => {
      this.replaceNodeContentsHook.reLayoutNodeFragments(nodeId);
    });

    this.initialWorklist$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(initialWorklist => {
      this.worklistSubject.next(initialWorklist);
    });
  }

  public selectedVariableChanged(variableIdentityId: number): void {
    this.targetVariableIdSubject.next(variableIdentityId);
  }

  public startInsertion(): void {
    this.phiInsertionStateSubject.next(PhiInsertionState.WORKLOOP);
  }

  public reset(): void {
    this.phiInsertionStateSubject.next(PhiInsertionState.CONFIGURE);
  }

  public triggerReLayout(nodeId: number): void {
    this.reLayoutSubject.next(nodeId);
  }
}
