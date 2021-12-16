import { slak } from '@ckompiler/ckompiler';
import {
  AfterViewInit,
  ApplicationRef,
  ChangeDetectionStrategy,
  Component,
  ComponentFactoryResolver,
  ElementRef,
  Injector,
  Input,
  ViewChild,
} from '@angular/core';
import * as d3Graphviz from 'd3-graphviz';
import { Graphviz, GraphvizOptions } from 'd3-graphviz';
import { IrFragmentComponent, irFragmentComponentSelector } from './components/ir-fragment/ir-fragment.component';
import { debounce, of, ReplaySubject, Subject, Subscription, takeUntil, timer } from 'rxjs';
import { SubscriptionDestroy } from '../utils/subscription-destroy';
import { BaseType } from 'd3';
import jsCompile = slak.ckompiler.jsCompile;
import createGraphviz = slak.ckompiler.analysis.createGraphviz;
import graphvizOptions = slak.ckompiler.graphvizOptions;

function measureTextAscent(text: string): number {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d')!;
  ctx.font = '16px "Roboto"';
  const metrics = ctx.measureText(text);
  return metrics.actualBoundingBoxAscent;
}

@Component({
  selector: 'cki-graph-view',
  templateUrl: './graph-view.component.html',
  styleUrls: ['./graph-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphViewComponent extends SubscriptionDestroy implements AfterViewInit {
  @ViewChild('graph')
  private graphRef!: ElementRef<HTMLDivElement>;

  @Input()
  public set sourceCode(code: string | null) {
    if (code !== null) {
      this.compileSource(code);
    }
  }

  private textSubscription?: Subscription;
  private graphviz?: Graphviz<BaseType, unknown, BaseType, unknown>;

  private readonly resizeSubject: Subject<DOMRectReadOnly> = new Subject();
  private readonly graphVizTextSubject: ReplaySubject<string> = new ReplaySubject<string>(1);

  constructor(
    private componentFactoryResolver: ComponentFactoryResolver,
    private injector: Injector,
    private applicationRef: ApplicationRef,
  ) {
    super();
  }

  private replaceTexts(): void {
    const componentFactory = this.componentFactoryResolver.resolveComponentFactory(IrFragmentComponent);
    const svgTextElements = Array.from(this.graphRef.nativeElement.querySelectorAll('text'));
    const maxAscent = Math.max(...svgTextElements.map(svgElem => measureTextAscent(svgElem.textContent ?? '')));
    for (const textElement of svgTextElements) {
      const text = textElement.textContent ?? '';

      const foreign = document.createElementNS('http://www.w3.org/2000/svg', 'foreignObject');
      const replaceableHost = document.createElement(irFragmentComponentSelector);
      foreign.appendChild(replaceableHost);

      const comp = componentFactory.create(this.injector, [], replaceableHost);
      comp.instance.irText = text;
      comp.instance.color = textElement.getAttribute('fill')!;
      this.applicationRef.attachView(comp.hostView);

      foreign.setAttribute('x', textElement.getAttribute('x')!);
      foreign.setAttribute('y', textElement.getAttribute('y')!);
      foreign.setAttribute('width', '100%');
      foreign.setAttribute('height', '100%');

      foreign.setAttribute('transform', `translate(0, -${maxAscent})`);

      textElement.replaceWith(foreign);
    }
  }

  private compileSource(code: string): void {
    const cfgs = jsCompile(code);
    if (cfgs == null) {
      console.error('Compilation failed.');
      return;
    }

    const main = cfgs.find(cfg => cfg.f.name === 'main');

    if (!main) {
      console.error('No main');
      return;
    }

    const options = graphvizOptions(true, 16, 'Roboto', 'IR_TO_STRING');
    const graphvizText = createGraphviz(main, main.f.sourceText as string, options);
    this.graphVizTextSubject.next(graphvizText);
  }

  private subscribeToGraphvizText(): void {
    this.textSubscription?.unsubscribe();
    this.textSubscription = this.graphVizTextSubject.pipe(
      takeUntil(this.destroy$)
    ).subscribe(text => {
      if (this.graphviz) {
        this.graphviz.renderDot(text, () => this.replaceTexts());
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

    let wasFirst = true;

    this.resizeSubject.pipe(
      debounce(() => {
        const time$ = wasFirst ? of(0) : timer(500);
        wasFirst = false;
        return time$;
      }),
      takeUntil(this.destroy$)
    ).subscribe(rect => {
      this.graphRef.nativeElement.style.width = `${rect.width}px`;
      this.graphRef.nativeElement.style.height = `${rect.height}px`;
      this.graphviz?.width(rect.width);
      this.graphviz?.height(rect.height);

      this.subscribeToGraphvizText();
    });
  }

  public onResize(events: ResizeObserverEntry[]): void {
    const event = events.find(event => event.target === this.graphRef.nativeElement.parentElement);
    if (event && event.contentRect.width && event.contentRect.height) {
      this.resizeSubject.next(event.contentRect);
    }
  }
}
