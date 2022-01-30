import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Observable, of } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { PhiIrFragmentComponent } from '../phi-ir-fragment/phi-ir-fragment.component';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import Variable = slak.ckompiler.analysis.Variable;
import JSCompileResult = slak.ckompiler.JSCompileResult;

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

  public readonly compileResult$: Observable<JSCompileResult> = this.phiInsertionStateService.compileResult$;
  public readonly variables$: Observable<Variable[]> = this.phiInsertionStateService.variables$;

  public readonly variableControl: FormControl = new FormControl(null);

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private phiInsertionStateService: PhiInsertionStateService,
  ) {
  }
}
