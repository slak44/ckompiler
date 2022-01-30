import { ChangeDetectionStrategy, Component } from '@angular/core';
import { filter, map, Observable, of, shareReplay } from 'rxjs';
import { GraphViewHook } from '../../models/graph-view-hook.model';
import { ReplaceNodeContentsHook } from '../../graph-view-hooks/replace-node-contents';
import { removeHoverTitles } from '../../graph-view-hooks/remove-hover-titles';
import { Nullable, slak } from '@ckompiler/ckompiler';
import { CompileService, logCompileError } from '../../services/compile.service';
import { FormControl } from '@angular/forms';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { PhiIrFragmentComponent } from '../phi-ir-fragment/phi-ir-fragment.component';
import { DisableDblClick } from '../../graph-view-hooks/disable-dblclick';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import jsCompile = slak.ckompiler.jsCompile;
import CFG = slak.ckompiler.analysis.CFG;
import Variable = slak.ckompiler.analysis.Variable;
import phiEligibleVariables = slak.ckompiler.phiEligibleVariables;

@Component({
  selector: 'cki-phi-insertion-view',
  templateUrl: './phi-insertion-view.component.html',
  styleUrls: ['./phi-insertion-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [PhiIrFragmentComponent.provider, ReplaceNodeContentsHook],
})
export class PhiInsertionViewComponent {
  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly hooks: GraphViewHook[] = [removeHoverTitles, new DisableDblClick(), this.replaceNodeContents];

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
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  public readonly cfg$: Observable<CFG> = this.compileResult$.pipe(
    filter(compileResult => !!compileResult.cfgs),
    map(compileResult => compileResult.cfgs!.find(cfg => cfg.f.name === 'main')),
    filter((cfg): cfg is CFG => !!cfg),
  );

  public readonly variables$: Observable<Variable[]> = this.cfg$.pipe(
    map(cfg => phiEligibleVariables(cfg)),
  );

  public readonly variableControl: FormControl = new FormControl(null);

  public readonly targetVariable$: Observable<number | null> = controlValueStream<number | null>(this.variableControl);

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private compileService: CompileService,
  ) {
  }
}
