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
import { combineLatest, map, Subject, Subscription, takeUntil } from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { BaseType } from 'd3';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { CompileService } from '../../services/compile.service';
import { GraphOptionsComponent } from '../graph-options/graph-options.component';
import { GraphvizDatum } from './graphviz-datum';
import { catmullRomSplines } from './catmull-rom-splines';
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

function generateFrontierPath(frontierNodeIds: number[], graphNodes: GraphvizDatum[]): string {
  const frontierDatum = graphNodes.filter(datum => {
    const nodeId = parseInt(datum.id.match(/node(\d+)/)![1], 10);

    return frontierNodeIds.includes(nodeId);
  });

  const flatPoints: number[] = frontierDatum.flatMap(datum => [datum.center.x, datum.bbox.y]);

  if (flatPoints.length === 0) {
    return '';
  }

  const allPoints = [-9999, -999]
    .concat(flatPoints)
    .concat(9999, -999);

  return catmullRomSplines(allPoints, 1);
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

  private alterGraphSubscription?: Subscription;
  private graphviz?: Graphviz<BaseType, GraphvizDatum, BaseType, unknown>;

  private readonly resizeSubject: Subject<DOMRectReadOnly> = new Subject();

  private readonly foreignToTextMap: Map<SVGForeignObjectElement, SVGTextElement> = new Map();

  private readonly clickedNodeSubject: Subject<BasicBlock> = new Subject();
  private readonly clearClickedSubject: Subject<void> = new Subject();

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

  private showFrontierPath(graph: Element, graphNodes: GraphvizDatum[]): void {
    graph.appendChild(this.activeFrontierPath);

    const sub = this.clickedNodeSubject.subscribe((clickedNode) => {
      const frontierIds = arrayOf<BasicBlock>(clickedNode.dominanceFrontier).map(node => node.nodeId);

      this.activeFrontierPath.setAttribute('d', generateFrontierPath(frontierIds, graphNodes));
    });
    this.alterGraphSubscription?.add(sub);
  }

  private removeTitles(graphRef: HTMLDivElement): void {
    const titles = Array.from(graphRef.querySelectorAll('title'));
    for (const titleElem of titles) {
      titleElem.textContent = '';
    }
  }

  private handleClearClicked(graph: Element): void {
    const clearSub = this.clearClickedSubject.subscribe(() => {
      graph.querySelectorAll('polygon.clicked').forEach(e => e.classList.remove('clicked'));
      graph.querySelectorAll('polygon.frontier').forEach(e => e.classList.remove('frontier'));
      graph.querySelectorAll('polygon.idom').forEach(e => e.classList.remove('idom'));
      this.activeFrontierPath.setAttribute('d', '');
    });
    this.alterGraphSubscription?.add(clearSub);
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

  private alterGraph(printingType: string, graphNodes: GraphvizDatum[]): void {
    const graphRef = this.graphRef.nativeElement;
    const graph = graphRef.querySelector('g.graph')!;

    this.showFrontierPath(graph, graphNodes);
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

    e.addEventListener('mouseover', () => e.classList.add('hovered'));
    e.addEventListener('mouseout', () => e.classList.remove('hovered'));

    e.addEventListener('click', () => {
      const node = getNodeById(cfg, nodeId);
      if (e.classList.contains('clicked')) {
        // Clicking already selected node should unselect
        this.clearClickedSubject.next();
      } else {
        this.clickedNodeSubject.next(node);
      }
    });

    const sub = this.clickedNodeSubject.subscribe((clickedNode) => {
      setClassIf(e, 'clicked', clickedNode.nodeId === nodeId);
      const frontierIds = arrayOf<BasicBlock>(clickedNode.dominanceFrontier).map(block => block.nodeId);
      setClassIf(e, 'frontier', frontierIds.includes(nodeId));
      setClassIf(e, 'idom', cfg.doms.get(clickedNode)!.nodeId === nodeId);
    });
    this.alterGraphSubscription?.add(sub);
  }

  private attributer(element: BaseType, datum: GraphvizDatum, cfg: CFG, graphNodes: GraphvizDatum[]): void {
    const parent = datum.parent;
    const parentIsG = parent && parent.tag === 'g' && /^node\d+$/.test(parent.key);
    if (datum.tag === 'polygon' && parentIsG) {
      const nodeId = parseInt(parent.key.slice('node'.length));
      this.configureNode(element as SVGPolygonElement, nodeId, cfg);
      graphNodes.push(datum);
    }
  }

  private subscribeToGraphvizText(): void {
    this.alterGraphSubscription?.unsubscribe();
    this.alterGraphSubscription = combineLatest([
      this.compileService.compileResult$,
      this.graphOptions.printingValue$,
    ]).pipe(
      map(([compileResult, printingType]: [JSCompileResult, string]): [CFG | null, string | null, string] => {
        if (!compileResult.cfgs) {
          return [null, null, printingType];
        }

        const main = compileResult.cfgs.find(cfg => cfg.f.name === 'main');

        if (!main) {
          return [null, null, printingType];
        }

        const options = graphvizOptions(true, 16.5, 'Courier New', printingType);
        return [main, createGraphviz(main, main.f.sourceText as string, options), printingType];
      }),
      takeUntil(this.destroy$),
    ).subscribe(([cfg, text, printingType]: [CFG | null, string | null, string]): void => {
      if (this.graphviz && text && cfg) {
        this.revertAlterations();

        // Required for that API
        // eslint-disable-next-line @typescript-eslint/no-this-alias
        const self = this;

        const graphNodes: GraphvizDatum[] = [];

        this.graphviz
          .attributer(function (datum: GraphvizDatum): void {
            self.attributer(this, datum, cfg, graphNodes);
          })
          .renderDot(text, () => this.alterGraph(printingType, graphNodes));
      }
    });
  }

  public ngAfterViewInit(): void {
    const options: GraphvizOptions = {
      useWorker: true,
      fit: true,
    };

    this.graphviz = d3Graphviz.graphviz(this.graphRef.nativeElement, options);
    this.graphviz.onerror(error => console.error(error));

    this.resizeSubject.pipe(
      debounceAfterFirst(500),
      takeUntil(this.destroy$),
    ).subscribe(rect => {
      this.graphRef.nativeElement.style.width = `${rect.width}px`;
      this.graphRef.nativeElement.style.height = `${rect.height}px`;
      this.graphviz?.width(rect.width);
      this.graphviz?.height(rect.height);

      this.subscribeToGraphvizText();
    });
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
