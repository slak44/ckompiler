import { AlterGraphHook, GraphViewHook } from '../models/graph-view-hook.model';
import { takeUntil } from 'rxjs';
import { arrayOfCollection, BasicBlock } from '@ckompiler/ckompiler';
import { GraphvizDatum } from '../models/graphviz-datum.model';
import { getPolyDatumNodeId } from '../utils';
import { catmullRomSplines } from '@cki-utils/catmull-rom-splines';

function generateFrontierPath(frontierNodeIds: number[], graphNodes: GraphvizDatum[]): string {
  const frontierDatum = graphNodes.filter(datum => frontierNodeIds.includes(getPolyDatumNodeId(datum)));

  const flatPoints: number[] = frontierDatum.flatMap(datum => [datum.center.x, datum.bbox.y]);

  if (flatPoints.length === 0) {
    return '';
  }

  const allPoints = [-9999, -999]
    .concat(flatPoints)
    .concat(9999, -999);

  return catmullRomSplines(allPoints, 1);
}

const activeFrontierPath: SVGPathElement = document.createElementNS('http://www.w3.org/2000/svg', 'path');
activeFrontierPath.classList.add('frontier-path');

const addFrontierPath: AlterGraphHook = (graphView, cfg, printingType, graph, graphNodesSelection) => {
  graph.appendChild(activeFrontierPath);

  graphView.clickedNode$.pipe(
    takeUntil(graphView.rerender$),
  ).subscribe((clickedNode) => {
    const frontierIds = arrayOfCollection<BasicBlock>(clickedNode.dominanceFrontier).map(node => node.nodeId);
    const frontierPath = generateFrontierPath(frontierIds, graphNodesSelection.data());

    activeFrontierPath.setAttribute('d', frontierPath);
  });

  graphView.clearClicked$.pipe(
    takeUntil(graphView.rerender$),
  ).subscribe(() => {
    activeFrontierPath.setAttribute('d', '');
  });
};

const revertAlteration = (): void => {
  const pathParent = activeFrontierPath.parentNode;
  if (pathParent) {
    activeFrontierPath.setAttribute('d', '');
    pathParent.removeChild(activeFrontierPath);
  }
};

export const frontierPath: GraphViewHook = {
  alterGraph: addFrontierPath,
  revertAlteration
};
