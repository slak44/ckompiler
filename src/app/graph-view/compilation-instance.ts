import {
  combineLatestWith,
  distinctUntilChanged,
  filter,
  map,
  Observable,
  OperatorFunction,
  pipe,
  ReplaySubject,
  shareReplay,
  tap,
  throttleTime,
} from 'rxjs';
import {
  CFG,
  ISAType,
  jsCompile,
  JSCompileResult,
  Nullable,
  phiEligibleVariables,
  Variable,
} from '@ckompiler/ckompiler';
import { currentTargetFunction, defaultFunctionName } from '@cki-settings';

function logCompileError(e: unknown): void {
  const err = e as Error & { originalStack?: string };
  if (err.originalStack) {
    console.error(err.message, err.originalStack);
  }
  console.error(err);
}

export function compileCode(
  isaType$: Observable<ISAType>,
  skipSSARename: boolean = false,
): OperatorFunction<string, JSCompileResult> {
  return pipe(
    // Forcefully throttle compilations, so death loops are avoided
    throttleTime(500),
    combineLatestWith(isaType$),
    map(([code, isaType]) => {
      try {
        return jsCompile(code, skipSSARename, isaType);
      } catch (e) {
        logCompileError(e);
        return null;
      }
    }),
    filter((compileResult: Nullable<JSCompileResult>): compileResult is JSCompileResult => !!compileResult),
    shareReplay({ bufferSize: 1, refCount: false }),
  );
}

export class CompilationInstance {
  private readonly selectedCFGSubject: ReplaySubject<CFG> = new ReplaySubject(1);

  public readonly cfg$: Observable<CFG> = this.selectedCFGSubject;

  public readonly cfgs$: Observable<CFG[]> = this.compileResult$.pipe(
    distinctUntilChanged(),
    map(compileResult => compileResult.cfgs),
    filter((cfgs): cfgs is CFG[] => !!cfgs && cfgs.length > 0),
    tap(cfgs => {
      const cfgByLastFunction = currentTargetFunction.snapshot
        ? cfgs.find(cfg => cfg.functionIdentifier.toString() === currentTargetFunction.snapshot)
        : undefined;
      const cfg = cfgByLastFunction ??
        cfgs.find(cfg => cfg.functionIdentifier.name === defaultFunctionName.snapshot) ?? cfgs[0];
      this.updateSelected(cfg);
    }),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  public readonly variables$: Observable<Variable[]> = this.cfg$.pipe(
    map(cfg => phiEligibleVariables(cfg)),
  );

  constructor(
    public readonly compileResult$: Observable<JSCompileResult>,
  ) {
  }

  public updateSelected(cfg: CFG): void {
    currentTargetFunction.update(cfg.functionIdentifier.toString());
    this.selectedCFGSubject.next(cfg);
  }
}
