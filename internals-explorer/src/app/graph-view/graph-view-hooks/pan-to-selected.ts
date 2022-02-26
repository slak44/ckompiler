import { GraphNodesSelection, GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { slak } from '@ckompiler/ckompiler';
import CFG = slak.ckompiler.analysis.CFG;
import { Observable, takeUntil } from 'rxjs';
import { getPolyDatumNodeId } from '@cki-graph-view/utils';

export class PanToSelected implements GraphViewHook {
  constructor(
    private selectedNodeId$: Observable<number>,
  ) {
  }

  public alterGraph(
    graphView: GraphViewComponent,
    cfg: CFG,
    printingType: string,
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
