import { GraphViewHook } from '../models/graph-view-hook.model';
import {
  ApplicationRef,
  ComponentFactory,
  ComponentFactoryResolver,
  Inject,
  Injectable,
  Injector,
  Type,
} from '@angular/core';
import { GraphViewComponent } from '../components/graph-view/graph-view.component';
import { slak } from '@ckompiler/ckompiler';
import { FRAGMENT_COMPONENT, FragmentComponent, GENERIC_FRAGMENT_HOST } from '../models/fragment-component.model';
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

  private readonly componentFactory: ComponentFactory<FragmentComponent> =
    this.componentFactoryResolver.resolveComponentFactory(this.fragmentComponentType);

  constructor(
    private componentFactoryResolver: ComponentFactoryResolver,
    private injector: Injector,
    private applicationRef: ApplicationRef,
    @Inject(FRAGMENT_COMPONENT) private fragmentComponentType: Type<FragmentComponent>,
  ) {
  }

  public alterGraph(graphView: GraphViewComponent, cfg: CFG, printingType: string, graph: Element): void {
    const svgTextElements = Array.from(graph.querySelectorAll('text'));
    const textAscents = svgTextElements.map(svgElem => measureTextAscent(svgElem.textContent ?? '', 'Fira Code'));
    const maxAscent = Math.max(...textAscents);
    for (const textElement of svgTextElements) {
      const text = textElement.textContent ?? '';

      const foreign = document.createElementNS('http://www.w3.org/2000/svg', 'foreignObject');
      const replaceableHost = document.createElement(GENERIC_FRAGMENT_HOST);
      foreign.appendChild(replaceableHost);

      const comp = this.componentFactory.create(this.injector, [], replaceableHost);
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
