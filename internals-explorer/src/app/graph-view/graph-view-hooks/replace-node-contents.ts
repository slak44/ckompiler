import { GraphViewHook } from '../models/graph-view-hook.model';
import { GraphViewFragmentComponent, graphViewFragmentSelector } from '../components/graph-view-fragment/graph-view-fragment.component';
import { ApplicationRef, ComponentFactoryResolver, Injectable, Injector } from '@angular/core';
import { GraphViewComponent } from '../components/graph-view/graph-view.component';
import { slak } from '@ckompiler/ckompiler';
import CFG = slak.ckompiler.analysis.CFG;

function measureTextAscent(text: string, fontName: string): number {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d')!;
  ctx.font = `16px "${fontName}"`;
  const metrics = ctx.measureText(text);
  return metrics.actualBoundingBoxAscent;
}

@Injectable()
export class ReplaceNodeContentsHook implements GraphViewHook {
  private readonly foreignToTextMap: Map<SVGForeignObjectElement, SVGTextElement> = new Map();

  constructor(
    private componentFactoryResolver: ComponentFactoryResolver,
    private injector: Injector,
    private applicationRef: ApplicationRef,
  ) {
  }

  public alterGraph(graphView: GraphViewComponent, cfg: CFG, printingType: string, graph: Element): void {
    const componentFactory = this.componentFactoryResolver.resolveComponentFactory(GraphViewFragmentComponent);
    const svgTextElements = Array.from(graph.querySelectorAll('text'));
    const textAscents = svgTextElements.map(svgElem => measureTextAscent(svgElem.textContent ?? '', 'Fira Code'));
    const maxAscent = Math.max(...textAscents);
    for (const textElement of svgTextElements) {
      const text = textElement.textContent ?? '';

      const foreign = document.createElementNS('http://www.w3.org/2000/svg', 'foreignObject');
      const replaceableHost = document.createElement(graphViewFragmentSelector);
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

  public revertAlteration(): void {
    for (const [foreign, text] of this.foreignToTextMap) {
      foreign.replaceWith(text);
    }
    this.foreignToTextMap.clear();
  }
}
