import { combineLatest, map, Observable, ReplaySubject, shareReplay } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import Variable = slak.ckompiler.analysis.Variable;
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;

export class TargetVariableState {
  private readonly targetVariableIdSubject: ReplaySubject<number> = new ReplaySubject<number>(1);

  public readonly targetVariable$: Observable<Variable>;

  public readonly variableName$: Observable<string>;

  constructor(instance: CompilationInstance) {
    this.targetVariable$ = combineLatest([
      this.targetVariableIdSubject,
      instance.cfg$,
    ]).pipe(
      map(([identityId, cfg]) => phiEligibleVariables(cfg).find(variable => variable.identityId === identityId)!),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    this.variableName$ = this.targetVariable$.pipe(
      map(variable => variable.name),
    );
  }

  public selectedVariableChanged(variableIdentityId: number): void {
    this.targetVariableIdSubject.next(variableIdentityId);
  }
}
