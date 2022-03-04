import { filter, map, Observable, OperatorFunction, pipe, shareReplay } from 'rxjs';
import { Nullable, slak } from '@ckompiler/ckompiler';
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

export function compileCode(): OperatorFunction<string, JSCompileResult> {
  return pipe(
    map(code => {
      try {
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
}

export class CompilationInstance {
  public readonly cfg$: Observable<CFG> = this.compileResult$.pipe(
    filter(compileResult => !!compileResult.cfgs),
    map(compileResult => compileResult.cfgs!.find(cfg => cfg.f.name === 'main')),
    filter((cfg): cfg is CFG => !!cfg),
  );

  public readonly variables$: Observable<Variable[]> = this.cfg$.pipe(
    map(cfg => phiEligibleVariables(cfg)),
  );

  constructor(
    private compileResult$: Observable<JSCompileResult>,
  ) {
  }
}
