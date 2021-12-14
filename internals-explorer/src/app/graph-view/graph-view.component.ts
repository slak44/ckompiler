import { slak } from '@ckompiler/ckompiler';
import {
  AfterViewInit,
  ApplicationRef,
  ChangeDetectionStrategy,
  Component,
  ComponentFactoryResolver,
  ElementRef,
  Injector,
  ViewChild,
} from '@angular/core';
import * as d3Graphviz from 'd3-graphviz';
import { GraphvizOptions } from 'd3-graphviz';
import { IrFragmentComponent, irFragmentComponentSelector } from './components/ir-fragment/ir-fragment.component';
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import arrayOf = slak.ckompiler.arrayOf;
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
export class GraphViewComponent implements AfterViewInit {
  @ViewChild('graph')
  private graphRef!: ElementRef<HTMLDivElement>;

  public blocks?: BasicBlock[];

  public graphvizText!: string;

  constructor(
    private componentFactoryResolver: ComponentFactoryResolver,
    private injector: Injector,
    private applicationRef: ApplicationRef,
  ) {
    const cfgs = jsCompile(`
    int main() {
  int first = 123;
  int second = first / 2;
  if (first > 5346) {
    first += 56;
  } else {
    second -= 22;
  }

  while (234 + first) {
    first--;
  }

  for (int i = 23; i < 66; i++) {
    second = first + second / i;
  }

  double d = 32.23;
  do {
    d += 2;
  } while (d < 123.1234);

  return first * second;
}

`);
    if (cfgs == null) {
      console.error('Compilation failed.');
      return;
    }

    const main = cfgs.find(cfg => cfg.f.name === 'main');

    if (!main) {
      console.error('No main');
      return;
    }

    this.blocks = arrayOf<BasicBlock>(main.nodes);
    const options = graphvizOptions(true, 16, 'Roboto', 'IR_TO_STRING');
    this.graphvizText = createGraphviz(main, main.f.sourceText as string, options);
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

  public ngAfterViewInit(): void {
    const options: GraphvizOptions = {
      useWorker: true,
      height: window.innerHeight,
      width: window.innerWidth,
      fit: true,
    };
    const graph = d3Graphviz.graphviz(this.graphRef.nativeElement, options);

    graph.onerror(error => console.error(error));

    graph.renderDot(this.graphvizText, () => this.replaceTexts());
  }
}
