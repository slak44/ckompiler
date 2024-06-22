import { CFG, CodePrintingMethods } from '@ckompiler/ckompiler';
import { Selection } from 'd3';
import { GraphvizDatum } from './graphviz-datum.model';
import { GraphViewComponent } from '../components/graph-view/graph-view.component';

export type GraphNodesSelection = Selection<SVGPolygonElement, GraphvizDatum, Element, unknown>;

export type AlterGraphHook = (
  graphView: GraphViewComponent,
  cfg: CFG,
  printingType: CodePrintingMethods,
  graph: Element,
  graphNodesSelection: GraphNodesSelection
) => void;

export interface GraphViewHook {
  alterGraph: AlterGraphHook;
  revertAlteration?: () => void;
}
