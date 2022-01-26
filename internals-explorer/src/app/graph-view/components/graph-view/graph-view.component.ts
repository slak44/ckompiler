import { slak } from '@ckompiler/ckompiler';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Input,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import * as d3Graphviz from 'd3-graphviz';
import { Graphviz, GraphvizOptions } from 'd3-graphviz';
import { combineLatest, distinctUntilChanged, first, map, Observable, Subject, switchMap, takeUntil, tap } from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import * as d3 from 'd3';
import { BaseType } from 'd3';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { CompileService } from '../../services/compile.service';
import { GraphOptionsComponent } from '../graph-options/graph-options.component';
import { GraphvizDatum } from '../../models/graphviz-datum.model';
import { ZoomTransform } from 'd3-zoom';
import { ZoomView } from 'd3-interpolate';
import { GraphViewHook } from '../../models/graph-view-hook.model';
import { getDatumNodeId, getNodeById, setClassIf } from '../../utils';
import createGraphviz = slak.ckompiler.analysis.createGraphviz;
import graphvizOptions = slak.ckompiler.graphvizOptions;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import CFG = slak.ckompiler.analysis.CFG;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;

function setZoomOnElement(element: Element, transform: ZoomTransform): void {
  // Yeah, yeah, messing with library internals is bad, now shut up
  // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access,@typescript-eslint/no-explicit-any
  (element as any).__zoom = transform;
}

@Component({
  selector: 'cki-graph-view',
  templateUrl: './graph-view.component.html',
  styleUrls: ['./graph-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphViewComponent extends SubscriptionDestroy implements AfterViewInit, OnDestroy {
  @Input()
  public hooks: GraphViewHook[] = [];

  @ViewChild(GraphOptionsComponent)
  private graphOptions!: GraphOptionsComponent;

  @ViewChild('graph')
  private graphRef!: ElementRef<HTMLDivElement>;

  private graphviz?: Graphviz<BaseType, GraphvizDatum, BaseType, unknown>;

  private readonly resizeSubject: Subject<DOMRectReadOnly> = new Subject();

  private readonly clickedNodeSubject: Subject<BasicBlock> = new Subject();
  public readonly clickedNode$: Observable<BasicBlock> = this.clickedNodeSubject;

  private readonly clearClickedSubject: Subject<void> = new Subject();
  public readonly clearClicked$: Observable<void> = this.clearClickedSubject;

  private readonly rerenderSubject: Subject<void> = new Subject();
  public readonly rerender$: Observable<void> = this.rerenderSubject;

  constructor(
    private compileService: CompileService,
  ) {
    super();
  }

  private transitionToNode(graph: Element, target: GraphvizDatum): void {
    const svgRef = this.graphRef.nativeElement.querySelector('svg')!;

    const { x, y } = target.center;

    const transformFromZoom = ([x, y, k]: ZoomView): string => `scale(${k}) translate(${-x}, ${-y})`;
    const zoomTransform = d3.zoomTransform(svgRef);

    setZoomOnElement(graph, zoomTransform);

    const from: ZoomView = [zoomTransform.x, zoomTransform.y, zoomTransform.k];
    const to: ZoomView = [x, -y, zoomTransform.k];

    const interpolator = d3.interpolateZoom(from, to);

    const transition = d3
      .select(graph)
      .transition()
      .delay(50)
      .duration(500)
      .attrTween("transform", () => t => transformFromZoom(interpolator(t)))
      .on('end', () => setZoomOnElement(svgRef, d3.zoomTransform(graph)));

    const zb = this.graphviz!.zoomBehavior()!;

    // Library types are wrong
    // eslint-disable-next-line @typescript-eslint/no-explicit-any,@typescript-eslint/no-unsafe-argument
    zb.translateTo(transition as any, x, y);
  }

  private configureNode(e: SVGPolygonElement, nodeId: number, cfg: CFG): void {
    // Someone is overwriting us, somehow
    setTimeout(() => e.classList.add('node'), 0);

    const mouseover = () => e.classList.add('hovered');
    const mouseout = () => e.classList.remove('hovered');

    const nodeClick = () => {
      const node = getNodeById(cfg, nodeId);
      if (e.classList.contains('clicked')) {
        // Clicking already selected node should unselect
        e.classList.remove('clicked');
        this.clearClickedSubject.next();
      } else {
        this.clickedNodeSubject.next(node);
      }
    };

    e.addEventListener('mouseover', mouseover);
    e.addEventListener('mouseout', mouseout);
    e.addEventListener('click', nodeClick);

    this.rerenderSubject.pipe(first()).subscribe(() => {
      e.removeEventListener('mouseover', mouseover);
      e.removeEventListener('mouseout', mouseout);
      e.removeEventListener('click', nodeClick);
    });

    this.clickedNodeSubject.pipe(
      takeUntil(this.rerenderSubject),
    ).subscribe((clickedNode) => {
      setClassIf(e, 'clicked', clickedNode.nodeId === nodeId);
    });
  }

  private handleClearClicked(graph: Element): void {
    this.clearClickedSubject.pipe(
      takeUntil(this.rerenderSubject),
    ).subscribe(() => {
      graph.querySelectorAll('polygon.clicked').forEach(e => e.classList.remove('clicked'));
    });
  }

  private alterGraph(printingType: string, cfg: CFG): void {
    const graph = this.graphRef.nativeElement.querySelector('g.graph')!;

    const graphNodesSelection = d3.select(graph)
      .selectAll<SVGPolygonElement, GraphvizDatum>('g > polygon')
      .filter(datum => !isNaN(getDatumNodeId(datum)));
    const graphNodesData = graphNodesSelection.data();

    graphNodesSelection.nodes().forEach((node, idx) => {
      this.configureNode(node, getDatumNodeId(graphNodesData[idx]), cfg);
    });

    this.handleClearClicked(graph);

    for (const hook of this.hooks) {
      hook.alterGraph(this, cfg, printingType, graph, graphNodesSelection);
    }
  }

  private revertAlterations(): void {
    for (const hook of this.hooks) {
      hook.revertAlteration?.();
    }
  }

  public rerenderGraph(): Observable<void> {
    return combineLatest([
      this.compileService.compileResult$,
      this.graphOptions.printingValue$,
    ]).pipe(
      map(([compileResult, printingType]: [JSCompileResult, string]): void => {
        if (!compileResult.cfgs) {
          return;
        }

        const main = compileResult.cfgs.find(cfg => cfg.f.name === 'main');

        if (!main) {
          return;
        }

        const options = graphvizOptions(true, 16.5, 'Courier New', printingType);
        const text = createGraphviz(main, main.f.sourceText as string, options);

        if (!(this.graphviz && text && main)) {
          return;
        }

        this.rerenderSubject.next();
        this.revertAlterations();

        this.graphviz.renderDot(text, () => this.alterGraph(printingType, main));
      }),
    );
  }

  public ngAfterViewInit(): void {
    const options: GraphvizOptions = {
      useWorker: true,
      fit: true,
    };

    this.graphviz = d3Graphviz.graphviz(this.graphRef.nativeElement, options);
    this.graphviz.onerror(error => console.error(error));

    this.resizeSubject.pipe(
      distinctUntilChanged((rect1, rect2) => rect1.width === rect2.width && rect1.height === rect2.height),
      debounceAfterFirst(500),
      tap(rect => {
        this.graphRef.nativeElement.style.width = `${rect.width}px`;
        this.graphRef.nativeElement.style.height = `${rect.height}px`;
        this.graphviz?.width(rect.width);
        this.graphviz?.height(rect.height);
      }),
      switchMap(() => this.rerenderGraph()),
      takeUntil(this.destroy$),
    ).subscribe();
  }

  public override ngOnDestroy(): void {
    super.ngOnDestroy();
    (this.graphviz as unknown as { destroy(): void }).destroy();
  }

  public onResize(events: ResizeObserverEntry[]): void {
    const event = events.find(event => event.target === this.graphRef.nativeElement.parentElement);
    if (event && event.contentRect.width && event.contentRect.height) {
      this.resizeSubject.next(event.contentRect);
    }
  }
}
