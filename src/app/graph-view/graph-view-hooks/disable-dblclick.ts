import { GraphViewHook } from '../models/graph-view-hook.model';
import { CFG, CodePrintingMethods } from '@ckompiler/ckompiler';
import { GraphViewComponent } from '../components/graph-view/graph-view.component';

const stopPropagation = (event: Event) => event.stopImmediatePropagation();

export class DisableDblClick implements GraphViewHook {
  private graph: Element | undefined;

  public alterGraph(
    _graphView: GraphViewComponent,
    _cfg: CFG,
    _printingType: CodePrintingMethods,
    graph: Element
  ): void {
    this.graph = graph;
    graph.parentElement!.addEventListener('dblclick', stopPropagation, { capture: true });
  }

  public revertAlteration(): void {
    if (this.graph) {
      this.graph.removeEventListener('dblclick', stopPropagation, { capture: true });
    }
  }
}
