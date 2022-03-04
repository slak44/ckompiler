import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { slak } from '@ckompiler/ckompiler';
import { CompilationInstance, compileCode } from '@cki-graph-view/compilation-instance';
import { TargetVariableState } from '@cki-graph-view/target-variable-state';
import JSCompileResult = slak.ckompiler.JSCompileResult;

@Injectable()
export class RenamingStateService {
  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    compileCode()
  );

  public readonly compilationInstance: CompilationInstance = new CompilationInstance(this.compileResult$);

  public readonly varState: TargetVariableState = new TargetVariableState(this.compilationInstance);

  constructor(
    private compileService: CompileService,
  ) {
  }
}
