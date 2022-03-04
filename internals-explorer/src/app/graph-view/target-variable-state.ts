import { combineLatest, map, Observable, shareReplay, Subject } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import Variable = slak.ckompiler.analysis.Variable;
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;
import { CompilationInstance } from '@cki-graph-view/compilation-instance';

export class TargetVariableState {
  private readonly targetVariableIdSubject: Subject<number> = new Subject<number>();

  public readonly targetVariable$: Observable<Variable>;

  constructor(instance: CompilationInstance) {
    this.targetVariable$ = combineLatest([
      this.targetVariableIdSubject,
      instance.cfg$,
    ]).pipe(
      map(([identityId, cfg]) => phiEligibleVariables(cfg).find(variable => variable.identityId === identityId)!),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
  }

  public selectedVariableChanged(variableIdentityId: number): void {
    this.targetVariableIdSubject.next(variableIdentityId);
  }
}
