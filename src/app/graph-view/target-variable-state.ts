import { combineLatest, filter, map, Observable, shareReplay } from 'rxjs';
import { phiEligibleVariables, Variable } from '@ckompiler/ckompiler';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { Setting } from '@cki-settings';

export class TargetVariableState {
  public readonly targetVariable$: Observable<Variable>;

  public readonly variableName$: Observable<string>;

  constructor(instance: CompilationInstance, targetVariableIdSetting: Setting<number | null>) {
    this.targetVariable$ = combineLatest([
      targetVariableIdSetting.value$.pipe(
        filter((identityId): identityId is number => typeof identityId === 'number'),
      ),
      instance.cfg$,
    ]).pipe(
      map(([identityId, cfg]) => phiEligibleVariables(cfg).find(variable => variable.identityId === identityId)),
      filter((variable): variable is Variable => !!variable),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    this.variableName$ = this.targetVariable$.pipe(
      map(variable => variable.name),
    );
  }
}
