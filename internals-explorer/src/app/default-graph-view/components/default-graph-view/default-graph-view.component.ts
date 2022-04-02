import { ChangeDetectionStrategy, Component } from '@angular/core';
import { GraphViewFragmentComponent } from '../graph-view-fragment/graph-view-fragment.component';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { frontierPath } from '@cki-graph-view/graph-view-hooks/frontier-path';
import { nodeClickDominance } from '@cki-graph-view/graph-view-hooks/node-click-dominance';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';

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

  public readonly instance: CompilationInstance = new CompilationInstance(this.compileService.defaultCompileResult$);

  constructor(
    private readonly replaceNodeContents: ReplaceNodeContentsHook,
    private readonly compileService: CompileService,
  ) {
  }
}
