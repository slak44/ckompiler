import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { slak } from '@ckompiler/ckompiler';
import { Observable, ReplaySubject } from 'rxjs';
import CFG = slak.ckompiler.analysis.CFG;
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

export interface RelativeRect {
  graph: DOMRect;
  element: DOMRect;
}

export class StartNodeRect implements GraphViewHook {
  private readonly positionSubject: ReplaySubject<RelativeRect> = new ReplaySubject<RelativeRect>(1);

  public readonly position$: Observable<RelativeRect> = this.positionSubject;

  public alterGraph(graphView: GraphViewComponent, cfg: CFG, printingType: CodePrintingMethods, graph: Element): void {
    this.updatePosition(graph);
    graphView.rerender$.subscribe(() => this.updatePosition(graph));
  }

  private updatePosition(graph: Element): void {
    const startBlock = graph.querySelector('#node1 > polygon')!;
    this.positionSubject.next({
      graph: graph.getBoundingClientRect(),
      element: startBlock.getBoundingClientRect()
    });
  }
}
