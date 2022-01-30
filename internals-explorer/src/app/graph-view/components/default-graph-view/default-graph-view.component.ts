import { ChangeDetectionStrategy, Component } from '@angular/core';
import { GraphViewHook } from '../../models/graph-view-hook.model';
import { nodeClickDominance } from '../../graph-view-hooks/node-click-dominance';
import { removeHoverTitles } from '../../graph-view-hooks/remove-hover-titles';
import { ReplaceNodeContentsHook } from '../../graph-view-hooks/replace-node-contents';
import { frontierPath } from '../../graph-view-hooks/frontier-path';
import { CompileService } from '../../services/compile.service';
import { Observable } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { GraphViewFragmentComponent } from '../graph-view-fragment/graph-view-fragment.component';
import JSCompileResult = slak.ckompiler.JSCompileResult;

@Component({
  selector: 'cki-default-graph-view',
  templateUrl: './default-graph-view.component.html',
  styleUrls: ['./default-graph-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [GraphViewFragmentComponent.provider, ReplaceNodeContentsHook],
})
export class DefaultGraphViewComponent {
  public readonly hooks: GraphViewHook[] = [
    removeHoverTitles,
    nodeClickDominance,
    frontierPath,
    this.replaceNodeContents,
  ];

  public readonly compileResult$: Observable<JSCompileResult> = this.compileService.compileResult$;

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private compileService: CompileService,
  ) {
  }
}
