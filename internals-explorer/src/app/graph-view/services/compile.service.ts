import { Injectable } from '@angular/core';
import { BehaviorSubject, distinctUntilChanged, map, Observable, ReplaySubject, shareReplay } from 'rxjs';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { slak } from '@ckompiler/ckompiler';
import { compileCode } from '@cki-graph-view/compilation-instance';
import Diagnostic = slak.ckompiler.Diagnostic;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import arrayOf = slak.ckompiler.arrayOf;
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;
import getDiagnosticsStats = slak.ckompiler.getDiagnosticsStats;

@Injectable({
  providedIn: 'root',
})
export class CompileService {
  private readonly latestCrashSubject: BehaviorSubject<Error | null> = new BehaviorSubject<Error | null>(null);
  private readonly sourceTextSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly sourceText$: Observable<string> = this.sourceTextSubject.pipe(
    debounceAfterFirst(500),
    distinctUntilChanged(),
    shareReplay({ bufferSize: 1, refCount: false }),
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

  public readonly latestCrash$: Observable<Error | null> = this.latestCrashSubject;

  constructor() {
  }

  public changeSourceText(sourceText: string): void {
    this.sourceTextSubject.next(sourceText);
  }

  public setLatestCrash(error: Error | null): void {
    this.latestCrashSubject.next(error);
  }
}
