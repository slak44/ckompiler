import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Observable, of } from 'rxjs';
import { GraphViewHook } from '../../models/graph-view-hook.model';
import { ReplaceNodeContentsHook } from '../../graph-view-hooks/replace-node-contents';
import { removeHoverTitles } from '../../graph-view-hooks/remove-hover-titles';

@Component({
  selector: 'cki-phi-insertion-view',
  templateUrl: './phi-insertion-view.component.html',
  styleUrls: ['./phi-insertion-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PhiInsertionViewComponent {
  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly hooks: GraphViewHook[] = [removeHoverTitles, this.replaceNodeContents];

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook
  ) {
  }
}
