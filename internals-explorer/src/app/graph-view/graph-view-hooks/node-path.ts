import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { slak } from '@ckompiler/ckompiler';
import { combineLatest, Observable, takeUntil, tap } from 'rxjs';
import * as d3 from 'd3';
import { BaseGraphvizDatum } from '@cki-graph-view/models/graphviz-datum.model';
import { catmullRomSplines } from '@cki-utils/catmull-rom-splines';
import { getNodeById } from '@cki-graph-view/utils';
import CFG = slak.ckompiler.analysis.CFG;
import arrayOf = slak.ckompiler.arrayOf;
import PhiInstruction = slak.ckompiler.analysis.PhiInstruction;
import Variable = slak.ckompiler.analysis.Variable;
import getDefinitionLocations = slak.ckompiler.getDefinitionLocations;
import IRInstruction = slak.ckompiler.analysis.IRInstruction;
import { measureWidth } from '@cki-utils/measure-text';
import { ORIGINAL_Y, ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';

type TwoPoints = [number, number, number, number];

function getEdgePath(edge: SVGGElement): TwoPoints {
  const path = edge.querySelector('path')!;
  const arrowTip = edge.querySelector('polygon')!;
  const { x: startX, y: startY } = path.getPointAtLength(0);
  const { x: targetX, y: targetY, width, height } = arrowTip.getBBox();

  return [targetX + width / 2, targetY + height / 2, startX, startY];
}

export class NodePath implements GraphViewHook {
  private definitionIdx: Record<number, number> = {};

  private readonly edgePoints: Map<string, TwoPoints> = new Map();

  private readonly renderedPaths: SVGPathElement[] = [];

  private cfg!: CFG;
  private graphView!: GraphViewComponent;

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private targetVariable$: Observable<Variable>,
    private paths$: Observable<number[][] | undefined>,
  ) {
  }

  private clearRenderedPaths(): void {
    while (this.renderedPaths.length > 0) {
      this.renderedPaths.shift()!.remove();
    }
  }

  private positionUntilVariableText(variable: Variable, nodeId: number, varDefNodeId: number): [number, number] {
    const node = getNodeById(this.cfg, nodeId);
    const nodePhi = arrayOf<PhiInstruction>(node.phi);

    let index: number;
    let irString: string;
    let text: string;

    const irIndex = this.definitionIdx[nodeId];
    if (irIndex !== undefined && irIndex !== -1) {
      // Definition is in IR
      const irDefinition = arrayOf<IRInstruction>(node.ir)[irIndex];
      index = nodePhi.length + irIndex;
      irString = irDefinition.toString();
      text = variable.name;
    } else {
      // Definition is in φ
      index = nodePhi.findIndex(phi => phi.variable.identityId === variable.identityId);
      irString = nodePhi[index].toString();
      text = `BB${varDefNodeId} v0`;
    }

    // +1 due to the BBx: header
    index++;

    const middleOfText = irString.indexOf(text) + text.length / 2;
    const widthUntilText = measureWidth(irString.slice(0, middleOfText));

    const nodeElement = this.graphView.getGroupByNodeId(nodeId);
    // nth-type is indexed from 1, so +1
    const fragment: SVGForeignObjectElement = nodeElement.querySelector(`foreignObject:nth-of-type(${index + 1})`)!;

    const xStart = parseInt(fragment.getAttribute('x')!, 10);

    const yPos = parseInt(fragment.dataset[ORIGINAL_Y]!, 10);

    return [xStart + widthUntilText, yPos];
  }

  private newPath(nodeIds: number[], targetVariable: Variable): SVGPathElement {
    const points: number[] = [];

    const varDefNodeId = nodeIds[nodeIds.length - 1];

    for (let i = 0; i < nodeIds.length - 1; i++) {
      // Reverse, because the path follows predecessors
      const edgeKey = `node${nodeIds[i + 1]}->node${nodeIds[i]}`;
      const edgeValues = this.edgePoints.get(edgeKey)!;
      points.push(...edgeValues);
    }

    const ascent = this.replaceNodeContents.getMaxAscent();

    const firstEdge = points.slice(0, 4);
    const phiPosition = this.positionUntilVariableText(targetVariable, nodeIds[0], varDefNodeId);
    const phiPosYCorrection = firstEdge[1] > phiPosition[1] ? ascent : 0;
    phiPosition[1] += phiPosYCorrection;

    const lastEdge = points.slice(-4);
    const definitionPosition = this.positionUntilVariableText(targetVariable, varDefNodeId, varDefNodeId);
    const defPosYCorrection = lastEdge[3] > definitionPosition[1] ? 0 : -ascent;
    definitionPosition[1] += defPosYCorrection;

    points.unshift(...phiPosition, phiPosition[0], firstEdge[1]);
    points.push(definitionPosition[0], lastEdge[3], ...definitionPosition);

    const spline = catmullRomSplines(points, 0.2);

    const svgPath: SVGPathElement = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    svgPath.classList.add('node-path');
    svgPath.setAttribute('d', spline);

    return svgPath;
  }

  public alterGraph(graphView: GraphViewComponent, cfg: CFG, printingType: string, graph: Element): void {
    this.graphView = graphView;
    this.cfg = cfg;

    const edgeElements: SVGGElement[] = Array.from(graph.querySelectorAll('g.edge'));

    for (const edgeElement of edgeElements) {
      const datum = d3.select<SVGGElement, BaseGraphvizDatum>(edgeElement).datum();

      this.edgePoints.set(datum.key, getEdgePath(edgeElement));
    }

    combineLatest([
      this.paths$,
      this.targetVariable$.pipe(
        tap(targetVariable => {
          this.definitionIdx = JSON.parse(getDefinitionLocations(targetVariable, cfg)) as Record<number, number>;
        }),
      ),
    ]).pipe(
      takeUntil(graphView.rerender$),
    ).subscribe(([paths, targetVariable]) => {
      if (!paths) {
        this.clearRenderedPaths();
        return;
      }

      for (const path of paths) {
        const svgPath = this.newPath(path, targetVariable);
        this.renderedPaths.push(svgPath);
      }
      graph.append(...this.renderedPaths);
    });
  }

  public revertAlteration(): void {
    this.clearRenderedPaths();
    this.edgePoints.clear();
    this.definitionIdx = {};
  }
}
