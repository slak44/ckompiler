import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { CFG, CodePrintingMethods } from '@ckompiler/ckompiler';
import { Observable, ReplaySubject } from 'rxjs';

export interface RelativeRect {
  graph: DOMRect;
  element: DOMRect;
}

export class StartNodeRect implements GraphViewHook {
  private readonly positionSubject: ReplaySubject<RelativeRect> = new ReplaySubject<RelativeRect>(1);

  public readonly position$: Observable<RelativeRect> = this.positionSubject;

  public alterGraph(
    graphView: GraphViewComponent,
    _cfg: CFG,
    _printingType: CodePrintingMethods,
    graph: Element
  ): void {
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
