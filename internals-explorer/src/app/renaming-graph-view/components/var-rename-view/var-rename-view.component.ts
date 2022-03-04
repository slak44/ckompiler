import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RenamingIrFragmentComponent } from '../renaming-ir-fragment/renaming-ir-fragment.component';
import { Observable, of } from 'rxjs';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { RenamingStateService } from '../../services/renaming-state.service';
import { slak } from '@ckompiler/ckompiler';
import JSCompileResult = slak.ckompiler.JSCompileResult;
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';

@Component({
  selector: 'cki-var-rename-view',
  templateUrl: './var-rename-view.component.html',
  styleUrls: ['./var-rename-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    RenamingIrFragmentComponent.provider,
    ReplaceNodeContentsHook,
  ],
})
export class VarRenameViewComponent {
  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly compileResult$: Observable<JSCompileResult> = this.renamingStateService.compileResult$;

  public readonly hooks: GraphViewHook[] = [
    this.replaceNodeContentsHook,
  ];

  constructor(
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
    private renamingStateService: RenamingStateService,
  ) {
  }
}
