import { GraphNodesSelection, GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { slak } from '@ckompiler/ckompiler';
import { Observable, takeUntil } from 'rxjs';
import { getPolyDatumNodeId } from '@cki-graph-view/utils';
import CFG = slak.ckompiler.analysis.CFG;
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

export class PanToSelected implements GraphViewHook {
  constructor(
    private readonly selectedNodeId$: Observable<number>,
  ) {
  }

  public alterGraph(
    graphView: GraphViewComponent,
    cfg: CFG,
    printingType: CodePrintingMethods,
    graph: Element,
    graphNodes: GraphNodesSelection
  ): void {
    this.selectedNodeId$.pipe(
      takeUntil(graphView.rerender$),
    ).subscribe(nodeId => {
      const nodeSelection = graphNodes.filter(datum => getPolyDatumNodeId(datum) === nodeId);
      const datum = nodeSelection.datum();

      graphView.transitionToNode(graph, datum);
    });
  }
}
