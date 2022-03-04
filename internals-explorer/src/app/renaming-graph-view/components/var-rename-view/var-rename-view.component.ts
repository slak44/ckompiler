import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RenamingIrFragmentComponent } from '../renaming-ir-fragment/renaming-ir-fragment.component';
import { Observable, of } from 'rxjs';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { RenamingStateService } from '../../services/renaming-state.service';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';

@Component({
  selector: 'cki-var-rename-view',
  templateUrl: './var-rename-view.component.html',
  styleUrls: ['./var-rename-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    RenamingIrFragmentComponent.provider,
    ReplaceNodeContentsHook,
    RenamingStateService,
  ],
})
export class VarRenameViewComponent {
  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly instance: CompilationInstance = this.renamingStateService.compilationInstance;

  public readonly hooks: GraphViewHook[] = [
    new DisableDblClick(),
    removeHoverTitles,
    this.replaceNodeContentsHook,
  ];

  constructor(
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
    private renamingStateService: RenamingStateService,
  ) {
  }
}
