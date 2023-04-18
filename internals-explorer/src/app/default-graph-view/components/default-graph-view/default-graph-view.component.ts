import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { GraphViewFragmentComponent } from '../graph-view-fragment/graph-view-fragment.component';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { frontierPath } from '@cki-graph-view/graph-view-hooks/frontier-path';
import { nodeClickDominance } from '@cki-graph-view/graph-view-hooks/node-click-dominance';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { Observable, of } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { currentPrintingType, isSpillOnly } from '@cki-settings';
import ISAType = slak.ckompiler.backend.ISAType;
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

@Component({
  selector: 'cki-default-graph-view',
  templateUrl: './default-graph-view.component.html',
  styleUrls: ['./default-graph-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [GraphViewFragmentComponent.provider, ReplaceNodeContentsHook],
})
export class DefaultGraphViewComponent {
  @Input()
  public isaType$: Observable<ISAType> = of(ISAType.X64);

  public readonly printingType$: Observable<CodePrintingMethods> = currentPrintingType.value$;

  public readonly isSpillOnly$: Observable<boolean> = isSpillOnly.value$;

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
