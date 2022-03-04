import { Injectable } from '@angular/core';
import {
  distinctUntilChanged,
  filter,
  map,
  Observable,
  OperatorFunction,
  pipe,
  ReplaySubject,
  shareReplay,
} from 'rxjs';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { Nullable, slak } from '@ckompiler/ckompiler';
import Diagnostic = slak.ckompiler.Diagnostic;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import jsCompile = slak.ckompiler.jsCompile;
import arrayOf = slak.ckompiler.arrayOf;
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;
import getDiagnosticsStats = slak.ckompiler.getDiagnosticsStats;
import clearAllAtomicCounters = slak.ckompiler.clearAllAtomicCounters;

export function logCompileError(e: unknown): void {
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

@Injectable({
  providedIn: 'root',
})
export class CompileService {
  private readonly sourceTextSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly sourceText$: Observable<string> = this.sourceTextSubject.pipe(
    debounceAfterFirst(500),
    distinctUntilChanged(),
  );

  public readonly defaultCompileResult$: Observable<JSCompileResult> = this.sourceText$.pipe(
    compileCode(),
  );

  public readonly allDiagnostics$: Observable<Diagnostic[]> = this.defaultCompileResult$.pipe(
    map(compileResult => {
      if (!compileResult.cfgs) {
        return compileResult.beforeCFGDiags;
      } else {
        const allCfgDiags = compileResult.cfgs.flatMap(cfg => arrayOf<Diagnostic>(cfg.diags));
        return compileResult.beforeCFGDiags.concat(allCfgDiags);
      }
    }),
  );

  public readonly diagnosticStats$: Observable<DiagnosticsStats> = this.allDiagnostics$.pipe(
    map(diagnostics => getDiagnosticsStats(diagnostics))
  );

  constructor() {
  }

  public changeSourceText(sourceText: string): void {
    this.sourceTextSubject.next(sourceText);
  }
}
