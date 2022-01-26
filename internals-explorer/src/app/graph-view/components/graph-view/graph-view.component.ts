import { slak } from '@ckompiler/ckompiler';
import {
  AfterViewInit,
  ApplicationRef,
  ChangeDetectionStrategy,
  Component,
  ComponentFactoryResolver,
  ElementRef,
  Injector,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import * as d3Graphviz from 'd3-graphviz';
import { Graphviz, GraphvizOptions } from 'd3-graphviz';
import { IrFragmentComponent, irFragmentComponentSelector } from '../ir-fragment/ir-fragment.component';
import {
  combineLatest,
  distinctUntilChanged,
  first,
  map,
  Observable,
  share,
  Subject,
  switchMap,
  takeUntil,
  tap,
} from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import * as d3 from 'd3';
import { BaseType } from 'd3';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { CompileService } from '../../services/compile.service';
import { GraphOptionsComponent } from '../graph-options/graph-options.component';
import { GraphvizDatum } from './graphviz-datum';
import { catmullRomSplines } from './catmull-rom-splines';
import { ZoomTransform } from 'd3-zoom';
import { ZoomView } from 'd3-interpolate';
import createGraphviz = slak.ckompiler.analysis.createGraphviz;
import graphvizOptions = slak.ckompiler.graphvizOptions;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import CFG = slak.ckompiler.analysis.CFG;
import arrayOf = slak.ckompiler.arrayOf;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;

function measureTextAscent(text: string, fontName: string): number {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d')!;
  ctx.font = `16px "${fontName}"`;
  const metrics = ctx.measureText(text);
  return metrics.actualBoundingBoxAscent;
}

function getNodeById(cfg: CFG, nodeId: number): BasicBlock {
  return arrayOf<BasicBlock>(cfg.nodes).find(node => node.nodeId === nodeId)!;
}

function setClassIf(e: Element, className: string, cond: boolean): void {
  if (cond) {
    e.classList.add(className);
  } else {
    e.classList.remove(className);
  }
}

function getDatumNodeId(datum: GraphvizDatum): number {
  const match = datum.parent.key.match(/^node(\d+)$/);
  if (!match) return NaN;
  return parseInt(match[1], 10);
}

function generateFrontierPath(frontierNodeIds: number[], graphNodes: GraphvizDatum[]): string {
  const frontierDatum = graphNodes.filter(datum => frontierNodeIds.includes(getDatumNodeId(datum)));

  const flatPoints: number[] = frontierDatum.flatMap(datum => [datum.center.x, datum.bbox.y]);

  if (flatPoints.length === 0) {
    return '';
  }

  const allPoints = [-9999, -999]
    .concat(flatPoints)
    .concat(9999, -999);

  return catmullRomSplines(allPoints, 1);
}

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
  @ViewChild(GraphOptionsComponent)
  private graphOptions!: GraphOptionsComponent;

  @ViewChild('graph')
  private graphRef!: ElementRef<HTMLDivElement>;

  private graphviz?: Graphviz<BaseType, GraphvizDatum, BaseType, unknown>;

  private readonly resizeSubject: Subject<DOMRectReadOnly> = new Subject();

  private readonly foreignToTextMap: Map<SVGForeignObjectElement, SVGTextElement> = new Map();

  private readonly clickedNodeSubject: Subject<BasicBlock> = new Subject();
  private readonly clickedNode$: Observable<BasicBlock> = this.clickedNodeSubject.pipe(
    distinctUntilChanged(),
    share()
  );

  private readonly clearClickedSubject: Subject<void> = new Subject();

  private readonly rerenderSubject: Subject<void> = new Subject();

  private readonly activeFrontierPath: SVGPathElement =
    document.createElementNS('http://www.w3.org/2000/svg', 'path');

  constructor(
    private componentFactoryResolver: ComponentFactoryResolver,
    private injector: Injector,
    private applicationRef: ApplicationRef,
    private compileService: CompileService,
  ) {
    super();

    this.activeFrontierPath.classList.add('frontier-path');
  }

  private transitionToNode(graph: Element, graphNodes: GraphvizDatum[], targetNode: BasicBlock): void {
    const svgRef = this.graphRef.nativeElement.querySelector('svg')!;

    const clickedDatum = graphNodes.find(datum => targetNode.nodeId === getDatumNodeId(datum));
    const { x, y } = clickedDatum!.center;

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

  private showFrontierPath(graph: Element, graphNodes: GraphvizDatum[]): void {
    graph.appendChild(this.activeFrontierPath);

    this.clickedNode$.pipe(
      takeUntil(this.rerenderSubject),
    ).subscribe((clickedNode) => {
      const frontierIds = arrayOf<BasicBlock>(clickedNode.dominanceFrontier).map(node => node.nodeId);

      this.activeFrontierPath.setAttribute('d', generateFrontierPath(frontierIds, graphNodes));
    });
  }

  private removeTitles(graphRef: HTMLDivElement): void {
    const titles = Array.from(graphRef.querySelectorAll('title'));
    for (const titleElem of titles) {
      titleElem.textContent = '';
    }
  }

  private handleClearClicked(graph: Element): void {
    this.clearClickedSubject.pipe(
      takeUntil(this.rerenderSubject),
    ).subscribe(() => {
      graph.querySelectorAll('polygon.clicked').forEach(e => e.classList.remove('clicked'));
      graph.querySelectorAll('polygon.frontier').forEach(e => e.classList.remove('frontier'));
      graph.querySelectorAll('polygon.idom').forEach(e => e.classList.remove('idom'));
      this.activeFrontierPath.setAttribute('d', '');
    });
  }

  private replaceTexts(graphRef: HTMLDivElement, printingType: string): void {
    const componentFactory = this.componentFactoryResolver.resolveComponentFactory(IrFragmentComponent);
    const svgTextElements = Array.from(graphRef.querySelectorAll('text'));
    const textAscents = svgTextElements.map(svgElem => measureTextAscent(svgElem.textContent ?? '', 'Fira Code'));
    const maxAscent = Math.max(...textAscents);
    for (const textElement of svgTextElements) {
      const text = textElement.textContent ?? '';

      const foreign = document.createElementNS('http://www.w3.org/2000/svg', 'foreignObject');
      const replaceableHost = document.createElement(irFragmentComponentSelector);
      foreign.appendChild(replaceableHost);

      const comp = componentFactory.create(this.injector, [], replaceableHost);
      comp.instance.text = text;
      comp.instance.printingType = printingType;
      comp.instance.color = textElement.getAttribute('fill')!;
      this.applicationRef.attachView(comp.hostView);

      foreign.setAttribute('x', textElement.getAttribute('x')!);
      foreign.setAttribute('y', textElement.getAttribute('y')!);
      foreign.setAttribute('width', '100%');
      foreign.setAttribute('height', '100%');

      foreign.setAttribute('transform', `translate(0, -${maxAscent})`);
      foreign.style.pointerEvents = 'none';

      this.foreignToTextMap.set(foreign, textElement);
      textElement.replaceWith(foreign);
    }
  }

  private alterGraph(printingType: string, cfg: CFG): void {
    const graphRef = this.graphRef.nativeElement;
    const graph = graphRef.querySelector('g.graph')!;

    const graphNodesSelection = d3.select(graph)
      .selectAll<SVGPolygonElement, GraphvizDatum>('g > polygon')
      .filter(datum => !isNaN(getDatumNodeId(datum)));
    const graphNodesData = graphNodesSelection.data();

    graphNodesSelection.nodes().map((element, idx) => {
      const nodeId = getDatumNodeId(graphNodesData[idx]);
      this.configureNode(element, nodeId, cfg);
    });

    this.showFrontierPath(graph, graphNodesData);
    this.handleClearClicked(graph);
    this.removeTitles(graphRef);
    this.replaceTexts(graphRef, printingType);
  }

  private revertAlterations(): void {
    for (const [foreign, text] of this.foreignToTextMap) {
      foreign.replaceWith(text);
    }
    this.foreignToTextMap.clear();

    const pathParent = this.activeFrontierPath.parentNode;
    if (pathParent) {
      this.activeFrontierPath.setAttribute('d', '');
      pathParent.removeChild(this.activeFrontierPath);
    }
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

    this.clickedNode$.pipe(
      takeUntil(this.rerenderSubject),
    ).subscribe((clickedNode) => {
      setClassIf(e, 'clicked', clickedNode.nodeId === nodeId);
      const frontierIds = arrayOf<BasicBlock>(clickedNode.dominanceFrontier).map(block => block.nodeId);
      setClassIf(e, 'frontier', frontierIds.includes(nodeId));
      setClassIf(e, 'idom', cfg.doms.get(clickedNode)!.nodeId === nodeId);
    });
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
