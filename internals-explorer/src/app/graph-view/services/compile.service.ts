import { Injectable } from '@angular/core';
import { BehaviorSubject, distinctUntilChanged, map, Observable, ReplaySubject, shareReplay } from 'rxjs';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { slak } from '@ckompiler/ckompiler';
import { compileCode } from '@cki-graph-view/compilation-instance';
import { currentPrintingType, sourceCode } from '@cki-settings';
import Diagnostic = slak.ckompiler.Diagnostic;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import arrayOf = slak.ckompiler.arrayOf;
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;
import getDiagnosticsStats = slak.ckompiler.getDiagnosticsStats;
import ISAType = slak.ckompiler.backend.ISAType;
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

@Injectable({
  providedIn: 'root',
})
export class CompileService {
  private readonly isaTypeSubject: ReplaySubject<ISAType> = new ReplaySubject(1);
  private readonly latestCrashSubject: BehaviorSubject<Error | null> = new BehaviorSubject<Error | null>(null);

  public readonly isaType$: Observable<ISAType> = this.isaTypeSubject.pipe(
    distinctUntilChanged(),
  );

  public readonly sourceText$: Observable<string> = sourceCode.value$.pipe(
    debounceAfterFirst(500),
    distinctUntilChanged(),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  public readonly defaultCompileResult$: Observable<JSCompileResult> = this.sourceText$.pipe(
    compileCode(this.isaType$),
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
    map(diagnostics => getDiagnosticsStats(diagnostics)),
  );

  public readonly latestCrash$: Observable<Error | null> = this.latestCrashSubject;

  constructor() {
  }

  public setLatestCrash(error: Error | null): void {
    if (error) {
      currentPrintingType.update(CodePrintingMethods.IR_TO_STRING);
    }
    this.latestCrashSubject.next(error);
  }

  public setISAType(isaType: ISAType): void {
    this.isaTypeSubject.next(isaType);
  }
}
