import { Injectable } from '@angular/core';
import { BehaviorSubject, combineLatest, filter, map, Observable, shareReplay, Subject } from 'rxjs';
import { CompileService, logCompileError } from '@cki-graph-view/services/compile.service';
import { Nullable, slak } from '@ckompiler/ckompiler';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import jsCompile = slak.ckompiler.jsCompile;
import Variable = slak.ckompiler.analysis.Variable;
import CFG = slak.ckompiler.analysis.CFG;
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;

export enum PhiInsertionState {
  CONFIGURE,
  WORKLOOP,
  DONE
}

@Injectable({
  providedIn: 'root',
})
export class PhiInsertionStateService {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    map(code => {
      try {
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
    this.cfg$
  ]).pipe(
    map(([identityId, cfg]) => phiEligibleVariables(cfg).find(variable => variable.identityId === identityId)!)
  );

  private readonly phiInsertionStateSubject: BehaviorSubject<PhiInsertionState> =
    new BehaviorSubject<PhiInsertionState>(PhiInsertionState.CONFIGURE);

  public readonly phiInsertionState$: Observable<PhiInsertionState> = this.phiInsertionStateSubject;

  constructor(
    private compileService: CompileService,
  ) {
  }

  public selectedVariableChanged(variableIdentityId: number): void {
    this.targetVariableIdSubject.next(variableIdentityId);
  }

  public startInsertion(): void {
    this.phiInsertionStateSubject.next(PhiInsertionState.WORKLOOP);
  }
}
