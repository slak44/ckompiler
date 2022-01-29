import { ChangeDetectionStrategy, Component } from '@angular/core';
import { filter, map, Observable, of, shareReplay } from 'rxjs';
import { GraphViewHook } from '../../models/graph-view-hook.model';
import { ReplaceNodeContentsHook } from '../../graph-view-hooks/replace-node-contents';
import { removeHoverTitles } from '../../graph-view-hooks/remove-hover-titles';
import { Nullable, slak } from '@ckompiler/ckompiler';
import { CompileService, logCompileError } from '../../services/compile.service';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import jsCompile = slak.ckompiler.jsCompile;

@Component({
  selector: 'cki-phi-insertion-view',
  templateUrl: './phi-insertion-view.component.html',
  styleUrls: ['./phi-insertion-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ReplaceNodeContentsHook],
})
export class PhiInsertionViewComponent {
  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly hooks: GraphViewHook[] = [removeHoverTitles, this.replaceNodeContents];

  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.sourceText$.pipe(
    map(code => {
      try {
        return jsCompile(code, true);
      } catch (e) {
        logCompileError(e);
        return null;
      }
    }),
    filter((compileResult: Nullable<JSCompileResult>): compileResult is JSCompileResult => !!compileResult),
    shareReplay({ bufferSize: 1, refCount: false })
  );

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private compileService: CompileService,
  ) {
  }
}
