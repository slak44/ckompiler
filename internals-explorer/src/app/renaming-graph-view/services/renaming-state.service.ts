import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { compileCode, CompileService } from '@cki-graph-view/services/compile.service';
import { slak } from '@ckompiler/ckompiler';
import JSCompileResult = slak.ckompiler.JSCompileResult;

@Injectable({
  providedIn: 'root',
})
export class RenamingStateService {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    compileCode()
  );

  constructor(
    private compileService: CompileService,
  ) {
  }
}
