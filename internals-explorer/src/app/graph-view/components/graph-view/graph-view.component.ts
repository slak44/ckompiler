import {
  BasicBlock,
  CFG,
  clearAllAtomicCounters,
  CodePrintingMethods,
  createGraphviz,
  GraphvizOptions as CKompilerGraphvizOptions,
  ISAType,
  JSCompileResult,
  restoreAllAtomicCounters,
  X64TargetOpts,
} from '@ckompiler/ckompiler';
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
import {
  combineLatest,
  combineLatestWith,
  debounceTime,
  distinctUntilChanged,
  filter,
  first,
  map,
  Observable,
  of,
  skip,
  Subject,
  switchMap,
  takeUntil,
  tap,
} from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import * as d3 from 'd3';
import { BaseType } from 'd3';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { GraphvizDatum } from '../../models/graphviz-datum.model';
import { ZoomTransform } from 'd3-zoom';
import { ZoomView } from 'd3-interpolate';
import { GraphViewHook } from '../../models/graph-view-hook.model';
import { getNodeById, getPolyDatumNodeId, runWithVariableVersions, setClassIf } from '../../utils';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { currentTargetFunction, Setting } from '@cki-settings';

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

  @Input()
  public disableVariableVersions: boolean = false;

  @Input()
  public printingType$!: Observable<CodePrintingMethods>;

  @Input()
  public isaType$: Observable<ISAType> = of(ISAType.X64);

  @Input()
  public noAllocOnlySpill$: Observable<boolean> = of(false);

  @Input()
  public transformSetting: Setting<ZoomTransform | null> | undefined;

  @Input()
  public selectedIdSetting: Setting<number | null> | undefined;

  @Input()
  public instance!: CompilationInstance;

  @ViewChild('graph')
  private readonly graphRef!: ElementRef<HTMLDivElement>;

  private readonly currentZoomTransformSubject: Subject<ZoomTransform> = new Subject<ZoomTransform>();

  private readonly graphTransformObserver: MutationObserver = new MutationObserver(
    this.handleTransformChanged.bind(this),
  );

  private graphviz?: Graphviz<BaseType, GraphvizDatum, BaseType, unknown>;

  private readonly resizeSubject: Subject<DOMRectReadOnly> = new Subject();

  private readonly clickedNodeSubject: Subject<BasicBlock> = new Subject();
  public readonly clickedNode$: Observable<BasicBlock> = this.clickedNodeSubject;

  private readonly clearClickedSubject: Subject<void> = new Subject();
  public readonly clearClicked$: Observable<void> = this.clearClickedSubject;

  private readonly rerenderSubject: Subject<void> = new Subject();
  public readonly rerender$: Observable<void> = this.rerenderSubject;

  private readonly groupToNodeId: Map<SVGGElement, number> = new Map();
  private readonly nodeIdToGroup: Map<number, SVGGElement> = new Map();

  constructor(private readonly compileService: CompileService) {
    super();
  }

  public getNodeIdByGroup(group: SVGGElement): number {
    const nodeId = this.groupToNodeId.get(group);

    if (nodeId === undefined) {
      console.error(group);
      throw new Error('No such element.');
    }

    return nodeId;
  }

  public getGroupByNodeId(nodeId: number): SVGGElement {
    const element = this.nodeIdToGroup.get(nodeId);

    if (!element) {
      throw new Error(`No such nodeId: ${nodeId}`);
    }

    return element;
  }

  public transitionToNode(graph: Element, target: GraphvizDatum): void {
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
      .attrTween('transform', () => t => transformFromZoom(interpolator(t)))
      .on('end', () => setZoomOnElement(svgRef, d3.zoomTransform(graph)));

    const zb = this.graphviz!.zoomBehavior()!;

    // Library types are wrong
    // eslint-disable-next-line @typescript-eslint/no-explicit-any,@typescript-eslint/no-unsafe-argument
    zb.translateTo(transition as any, x, y);
  }

  private configureNode(e: SVGPolygonElement, nodeId: number, cfg: CFG): void {
    this.groupToNodeId.set(e.parentNode as SVGGElement, nodeId);
    this.nodeIdToGroup.set(nodeId, e.parentNode as SVGGElement);

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

  private handleTransformChanged(mutations: MutationRecord[]): void {
    const mutationTargets = new Set(mutations.map(mutation => mutation.target));
    if (mutationTargets.size === 0) {
      console.error('MutationObserver sent 0 mutations. Check the observe/disconnect logic.');
      return;
    }
    if (mutationTargets.size !== 1) {
      console.error('MutationObserver sent more than 1 mutation. Some old graph is probably still being observed.');
    }

    const svgRef = Array.from(mutationTargets.values())[0].parentNode as SVGElement;
    if (this.transformSetting) {
      const zoomTransform = d3.zoomTransform(svgRef);
      this.currentZoomTransformSubject.next(zoomTransform);
    }
  }

  private setupZoomTransformEvents(graph: Element): void {
    if (!this.transformSetting) {
      return;
    }

    currentTargetFunction.value$.pipe(
      skip(1),
      takeUntil(this.rerender$)
    ).subscribe(() => {
      this.transformSetting!.update(null);
    });

    const svgRef = this.graphRef.nativeElement.querySelector('svg')!;
    this.currentZoomTransformSubject.pipe(
      distinctUntilChanged(),
      debounceTime(1000),
      takeUntil(this.rerender$),
    ).subscribe(zoomTransform => {
      this.transformSetting!.update(zoomTransform);
    });

    this.transformSetting.value$.pipe(
      filter((zoomTransform): zoomTransform is ZoomTransform => !!zoomTransform),
      takeUntil(this.rerender$),
    ).subscribe(zoomTransform => {
      this.currentZoomTransformSubject.next(zoomTransform);
      graph.setAttribute('transform', zoomTransform.toString());
      setZoomOnElement(svgRef, zoomTransform);
    });

    // The MutationObserver does not fire an initial value
    const zoomTransform = d3.zoomTransform(svgRef);
    this.currentZoomTransformSubject.next(zoomTransform);

    this.graphTransformObserver.observe(graph, { attributes: true, attributeFilter: ['transform'] });
  }

  private setupSelectedNodeIdEvents(cfg: CFG): void {
    if (!this.selectedIdSetting) {
      return;
    }

    this.clickedNodeSubject.pipe(
      distinctUntilChanged(),
      debounceAfterFirst(100),
      takeUntil(this.rerenderSubject),
    ).subscribe((clickedNode) => {
      this.selectedIdSetting?.update(clickedNode.nodeId);
    });

    this.selectedIdSetting.value$.pipe(
      takeUntil(this.rerenderSubject),
    ).subscribe(selectedNodeId => {
      if (selectedNodeId) {
        const node = getNodeById(cfg, selectedNodeId);
        if (node) {
          this.clickedNodeSubject.next(node);
        } else {
          console.error(`Cannot find node to click: ${selectedNodeId}`);
        }
      } else {
        this.clearClickedSubject.next();
      }
    });
  }

  private alterGraph(printingType: CodePrintingMethods, cfg: CFG): void {
    const graph = this.graphRef.nativeElement.querySelector('g.graph')!;

    const graphNodesSelection = d3.select(graph)
      .selectAll<SVGPolygonElement, GraphvizDatum>('g > polygon')
      .filter(datum => !isNaN(getPolyDatumNodeId(datum)));
    const graphNodesData = graphNodesSelection.data();

    graphNodesSelection.nodes().forEach((node, idx) => {
      this.configureNode(node, getPolyDatumNodeId(graphNodesData[idx]), cfg);
    });

    this.setupZoomTransformEvents(graph);
    this.handleClearClicked(graph);

    for (const hook of this.hooks) {
      hook.alterGraph(this, cfg, printingType, graph, graphNodesSelection);
    }

    // Do it after hook setup, so the initial node clicked event reaches hooks
    this.setupSelectedNodeIdEvents(cfg);
  }

  private revertAlterations(): void {
    this.graphTransformObserver.disconnect();

    for (const hook of this.hooks.reverse()) {
      hook.revertAlteration?.();
    }
    this.nodeIdToGroup.clear();
    this.groupToNodeId.clear();
  }

  private runCreateGraphviz(cfg: CFG, compileResult: JSCompileResult, options: CKompilerGraphvizOptions): string {
    return runWithVariableVersions(this.disableVariableVersions, () => {
      try {
        // eslint-disable-next-line @typescript-eslint/no-unsafe-call
        restoreAllAtomicCounters(compileResult.atomicCounters);

        const result = createGraphviz(cfg, cfg.f.sourceText as string, options);

        clearAllAtomicCounters();

        return result;
      } catch (eAgain) {
        this.compileService.setLatestCrash(eAgain as Error);
        throw eAgain;
      }
    });
  }

  public rerenderGraph(): Observable<void> {
    return combineLatest([
      this.printingType$,
      this.isaType$,
      this.noAllocOnlySpill$,
    ]).pipe(
      map(([printingType, isaType, noAllocOnlySpill]: [CodePrintingMethods, ISAType, boolean]) =>
        new CKompilerGraphvizOptions(
          16.5,
          'Courier New',
          true,
          printingType,
          true,
          isaType,
          X64TargetOpts.Companion.defaults,
          noAllocOnlySpill,
        ),
      ),
      combineLatestWith(this.instance.compileResult$, this.instance.cfg$, this.printingType$),
      debounceAfterFirst(50),
      map(([options, compileResult, cfg, printingType]): void => {
        const text = this.runCreateGraphviz(cfg, compileResult, options);

        if (!(this.graphviz && text)) {
          return;
        }

        this.compileService.setLatestCrash(null);
        this.rerenderSubject.next();
        this.revertAlterations();

        if (this.graphviz.zoomSelection()) {
          this.graphviz.resetZoom();
        }

        this.graphviz.renderDot(text, () => this.alterGraph(printingType, cfg));
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
    this.graphTransformObserver.disconnect();
  }

  public onResize(events: ResizeObserverEntry[]): void {
    const event = events.find(event => event.target === this.graphRef.nativeElement.parentElement);
    if (event && event.contentRect.width && event.contentRect.height) {
      this.resizeSubject.next(event.contentRect);
    }
  }
}
