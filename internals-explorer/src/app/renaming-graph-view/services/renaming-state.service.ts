import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
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

  private readonly isRenamingSubject: BehaviorSubject<boolean> = new BehaviorSubject<boolean>(false);

  public readonly isRenaming$: Observable<boolean> = this.isRenamingSubject;

  constructor(
    private compileService: CompileService,
  ) {
  }

  public startRenaming(): void {
    this.isRenamingSubject.next(true);
  }

  public reset(): void {
    this.isRenamingSubject.next(false);
  }
}
