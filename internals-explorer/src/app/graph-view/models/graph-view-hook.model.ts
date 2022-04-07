import { slak } from '@ckompiler/ckompiler';
import { Selection } from 'd3';
import { GraphvizDatum } from './graphviz-datum.model';
import { GraphViewComponent } from '../components/graph-view/graph-view.component';
import CFG = slak.ckompiler.analysis.CFG;
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

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
