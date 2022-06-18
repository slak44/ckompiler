import {
  distinctUntilChanged,
  filter,
  map,
  Observable,
  OperatorFunction,
  pipe,
  ReplaySubject,
  shareReplay,
  tap,
} from 'rxjs';
import { Nullable, slak } from '@ckompiler/ckompiler';
import { defaultFunctionName } from '@cki-settings';
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import Variable = slak.ckompiler.analysis.Variable;
import CFG = slak.ckompiler.analysis.CFG;
import clearAllAtomicCounters = slak.ckompiler.clearAllAtomicCounters;
import jsCompile = slak.ckompiler.jsCompile;

function logCompileError(e: unknown): void {
  const err = e as Error & { originalStack?: string };
  if (err.originalStack) {
    console.error(err.message, err.originalStack);
  }
  console.error(err);
}

export function compileCode(skipSSARename: boolean = false): OperatorFunction<string, JSCompileResult> {
  return pipe(
    map(code => {
      try {
        clearAllAtomicCounters();
        return jsCompile(code, skipSSARename);
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
    tap(cfgs => this.updateSelected(cfgs.find(cfg => cfg.f.name === defaultFunctionName.snapshot) ?? cfgs[0])),
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
    this.selectedCFGSubject.next(cfg);
  }
}
