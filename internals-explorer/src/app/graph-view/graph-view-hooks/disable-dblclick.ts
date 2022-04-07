import { GraphViewHook } from '../models/graph-view-hook.model';
import { slak } from '@ckompiler/ckompiler';
import { GraphViewComponent } from '../components/graph-view/graph-view.component';
import CFG = slak.ckompiler.analysis.CFG;
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

const stopPropagation = (event: Event) => event.stopImmediatePropagation();

export class DisableDblClick implements GraphViewHook {
  private graph: Element | undefined;

  public alterGraph(graphView: GraphViewComponent, cfg: CFG, printingType: CodePrintingMethods, graph: Element): void {
    this.graph = graph;
    graph.parentElement!.addEventListener('dblclick', stopPropagation, { capture: true });
  }

  public revertAlteration(): void {
    if (this.graph) {
      this.graph.removeEventListener('dblclick', stopPropagation, { capture: true });
    }
  }
}
