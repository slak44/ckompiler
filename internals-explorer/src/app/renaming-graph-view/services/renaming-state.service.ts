import { Injectable } from '@angular/core';
import { filter, map, Observable, shareReplay } from 'rxjs';
import { CompileService, logCompileError } from '@cki-graph-view/services/compile.service';
import { Nullable, slak } from '@ckompiler/ckompiler';
import clearAllAtomicCounters = slak.ckompiler.clearAllAtomicCounters;
import jsCompile = slak.ckompiler.jsCompile;
import JSCompileResult = slak.ckompiler.JSCompileResult;

@Injectable({
  providedIn: 'root',
})
export class RenamingStateService {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
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

  constructor(
    private compileService: CompileService,
  ) {
  }
}
