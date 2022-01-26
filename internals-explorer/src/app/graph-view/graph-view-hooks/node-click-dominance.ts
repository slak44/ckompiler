import { AlterGraphHook, GraphViewHook } from '../models/graph-view-hook.model';
import { getDatumNodeId, setClassIf } from '../utils';
import { takeUntil } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { GraphViewComponent } from '../components/graph-view/graph-view.component';
import CFG = slak.ckompiler.analysis.CFG;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import arrayOf = slak.ckompiler.arrayOf;

function configureNode(
  graphView: GraphViewComponent,
  e: SVGPolygonElement,
  nodeId: number,
  cfg: CFG,
  graph: Element
): void {
  graphView.clickedNode$.pipe(
    takeUntil(graphView.rerender$),
  ).subscribe((clickedNode) => {
    const frontierIds = arrayOf<BasicBlock>(clickedNode.dominanceFrontier).map(block => block.nodeId);
    setClassIf(e, 'frontier', frontierIds.includes(nodeId));
    setClassIf(e, 'idom', cfg.doms.get(clickedNode)!.nodeId === nodeId);
  });

  graphView.clearClicked$.pipe(
    takeUntil(graphView.rerender$),
  ).subscribe(() => {
    graph.querySelectorAll('polygon.frontier').forEach(poly => poly.classList.remove('frontier'));
    graph.querySelectorAll('polygon.idom').forEach(poly => poly.classList.remove('idom'));
  });
}

const alterGraph: AlterGraphHook = (graphView, cfg, printingType, graph, graphNodesSelection) => {
  const graphNodesData = graphNodesSelection.data();

  graphNodesSelection.nodes().map((element, idx) => {
    const nodeId = getDatumNodeId(graphNodesData[idx]);
    configureNode(graphView, element, nodeId, cfg, graph);
  });
};

export const nodeClickDominance: GraphViewHook = { alterGraph };
