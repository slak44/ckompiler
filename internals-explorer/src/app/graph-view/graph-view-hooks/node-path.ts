import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { slak } from '@ckompiler/ckompiler';
import { combineLatest, Observable, takeUntil, tap } from 'rxjs';
import * as d3 from 'd3';
import { BaseGraphvizDatum } from '@cki-graph-view/models/graphviz-datum.model';
import { catmullRomSplines } from '@cki-utils/catmull-rom-splines';
import { getNodeById } from '@cki-graph-view/utils';
import { measureWidth } from '@cki-utils/measure-text';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import CFG = slak.ckompiler.analysis.CFG;
import arrayOf = slak.ckompiler.arrayOf;
import PhiInstruction = slak.ckompiler.analysis.PhiInstruction;
import Variable = slak.ckompiler.analysis.Variable;
import getDefinitionLocations = slak.ckompiler.getDefinitionLocations;
import IRInstruction = slak.ckompiler.analysis.IRInstruction;

// Keep in sync with _algorithm.scss
const INSERTION_TRANSITION_MS = 150;

type EdgePoints = [number, number, number, number, number, number];

function getEdgePath(edge: SVGGElement): EdgePoints {
  const path = edge.querySelector('path')!;
  const arrowTip = edge.querySelector('polygon')!;
  const { x: startX, y: startY } = path.getPointAtLength(0);
  const { x: pathMiddleX, y: pathMiddleY } = path.getPointAtLength(path.getTotalLength() / 2);
  const { x: targetX, y: targetY, width, height } = arrowTip.getBBox();

  return [targetX + width / 2, targetY + height / 2, pathMiddleX, pathMiddleY, startX, startY];
}

export class NodePath implements GraphViewHook {
  private definitionIdx: Record<number, number> = {};

  private readonly edgePoints: Map<string, EdgePoints> = new Map();

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

  private getFragmentTextAndIndex(isForPhi: boolean, nodeId: number, variable: Variable): [string, number] {
    const node = getNodeById(this.cfg, nodeId);
    const nodePhi = arrayOf<PhiInstruction>(node.phi);

    let index: number;
    let irString: string;

    const irIndex = this.definitionIdx[nodeId];
    if (isForPhi || irIndex === undefined || irIndex === -1) {
      // Definition is in φ
      index = nodePhi.findIndex(phi => phi.variable.identityId === variable.identityId);
      if (index === -1) {
        throw new Error('Cannot find variable in block phis');
      }
      irString = nodePhi[index].toString();
    } else {
      // Definition is in IR
      const irDefinition = arrayOf<IRInstruction>(node.ir)[irIndex];
      index = nodePhi.length + irIndex;
      irString = irDefinition.toString();
    }

    // +1 due to the BBx: header
    index++;

    return [irString, index];
  }

  private positionUntilVariableText(fragmentData: [string, number], nodeId: number, searchText: string): [number, number] {
    const [irString, index] = fragmentData;

    const textIdx = irString.indexOf(searchText);
    if (textIdx === -1) {
      console.error(`Cannot find text ${searchText} in ${irString}`);
    }

    const middleOfText = textIdx + searchText.length / 2;
    const widthUntilText = measureWidth(irString.slice(0, middleOfText));

    const nodeElement = this.graphView.getGroupByNodeId(nodeId);
    // nth-type is indexed from 1, so +1
    const fragment: SVGForeignObjectElement = nodeElement.querySelector(`foreignObject:nth-of-type(${index + 1})`)!;

    const xStart = parseFloat(fragment.getAttribute('x')!);

    const transform = fragment.getAttribute('transform')!;
    const yTranslate = transform.match(/translate\(.*?,(.*?)\)/)!;
    const yPos = parseFloat(yTranslate[1]) + this.replaceNodeContents.getMaxAscent();

    return [xStart + widthUntilText, yPos];
  }

  private newPath(nodeIds: number[], targetVariable: Variable): SVGPathElement {
    const points: number[] = [];

    for (let i = 0; i < nodeIds.length - 1; i++) {
      // Reverse, because the path follows predecessors
      const edgeKey = `node${nodeIds[i + 1]}->node${nodeIds[i]}`;
      const edgeValues = this.edgePoints.get(edgeKey)!;
      points.push(...edgeValues);
    }

    const ascent = this.replaceNodeContents.getMaxAscent();

    const firstEdge = points.slice(0, 4);
    const phiFragmentData = this.getFragmentTextAndIndex(true, nodeIds[0], targetVariable);
    const phiPosition = this.positionUntilVariableText(phiFragmentData, nodeIds[0], `BB${nodeIds[1]} v0`);
    const isEdgeAbovePhi = firstEdge[1] > phiPosition[1];
    const phiPosYCorrection = isEdgeAbovePhi ? 0 : -ascent;
    phiPosition[1] += phiPosYCorrection;

    const varDefNodeId = nodeIds[nodeIds.length - 1];
    const lastEdge = points.slice(-4);
    const defFragmentData = this.getFragmentTextAndIndex(false, varDefNodeId, targetVariable);
    const definitionPosition = this.positionUntilVariableText(defFragmentData, varDefNodeId, targetVariable.name);
    const defPosYCorrection = lastEdge[3] > definitionPosition[1] ? 0 : -ascent;
    definitionPosition[1] += defPosYCorrection;

    points.unshift(...phiPosition, phiPosition[0], firstEdge[1]);
    points.push(definitionPosition[0], lastEdge[3], ...definitionPosition);

    const spline = catmullRomSplines(points, 0.3);

    const firstPoint = phiPosition;

    const arrowXOffset = 10;
    const arrowYOffset = 7;
    const direction = isEdgeAbovePhi ? 1 : -1;
    const leftPoint = [firstPoint[0] - arrowXOffset, firstPoint[1] + direction * arrowYOffset];
    const rightPoint = [firstPoint[0] + arrowXOffset, firstPoint[1] + direction * arrowYOffset];

    const arrowPath = 'M' + leftPoint.join() + 'L' + firstPoint.join() + 'L' + rightPoint.join();

    const svgPath: SVGPathElement = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    svgPath.classList.add('node-path');
    svgPath.setAttribute('d', spline + arrowPath);

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

      setTimeout(() => {
        for (const path of paths) {
          const svgPath = this.newPath(path, targetVariable);
          this.renderedPaths.push(svgPath);
        }
        graph.append(...this.renderedPaths);
      }, INSERTION_TRANSITION_MS);
    });
  }

  public revertAlteration(): void {
    this.clearRenderedPaths();
    this.edgePoints.clear();
    this.definitionIdx = {};
  }
}
