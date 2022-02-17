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
import { getNodeIdFromElement } from '@cki-graph-view/utils';
import { Observable } from 'rxjs';
import { partition } from 'lodash';
import CFG = slak.ckompiler.analysis.CFG;

function measureTextAscent(text: string, fontName: string): number {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d')!;
  ctx.font = `16px "${fontName}"`;
  const metrics = ctx.measureText(text);
  return metrics.actualBoundingBoxAscent;
}

const ORIGINAL_Y = 'originalY';

@Injectable()
export class ReplaceNodeContentsHook implements GraphViewHook {
  private readonly foreignToTextMap: Map<SVGForeignObjectElement, SVGTextElement> = new Map();

  private readonly componentFactory: ComponentFactory<FragmentComponent> =
    this.componentFactoryResolver.resolveComponentFactory(this.fragmentComponentType);

  private graph!: Element;
  private maxAscent!: number;

  public rerender$!: Observable<void>;

  constructor(
    private componentFactoryResolver: ComponentFactoryResolver,
    private injector: Injector,
    private applicationRef: ApplicationRef,
    @Inject(FRAGMENT_COMPONENT) private fragmentComponentType: Type<FragmentComponent>,
  ) {
  }

  private setForeignYPosition(foreignObject: SVGForeignObjectElement, yPosAttr: string | number): void {
    const yPos = +yPosAttr;
    foreignObject.setAttribute('transform', `translate(0, ${-this.maxAscent + yPos})`);
  }

  public reLayoutNodeFragments(nodeId: number): void {
    const node = this.graph.parentNode!.querySelector(`#node${nodeId}`)!;
    const texts = Array.from(node.querySelectorAll('foreignObject'));

    if (texts.length === 0) {
      return;
    }

    const [hiddenObjects, visibleObjects] = partition<SVGForeignObjectElement>(
      texts,
      foreignObject => foreignObject.firstElementChild!.classList.contains('hidden-fragment'),
    );

    const poly = node.querySelector('polygon')!;
    const polyHeight = poly.getBBox().height;

    const topY = parseInt(texts[0].dataset[ORIGINAL_Y]!, 10);
    const secondTopY = parseInt(texts?.[1]?.dataset?.[ORIGINAL_Y] || '', 10) || polyHeight;

    const elementHeight = secondTopY - topY;
    const emptySpace = polyHeight - elementHeight * visibleObjects.length;

    let currentOffset = topY + emptySpace / 2;
    for (const foreignObject of visibleObjects) {
      this.setForeignYPosition(foreignObject, currentOffset);
      currentOffset += elementHeight;
    }
  }

  public alterGraph(graphView: GraphViewComponent, cfg: CFG, printingType: string, graph: Element): void {
    this.graph = graph;
    this.rerender$ = graphView.rerender$;

    const svgTextElements = Array.from(graph.querySelectorAll('text'));
    const textAscents = svgTextElements.map(svgElem => measureTextAscent(svgElem.textContent ?? '', 'Fira Code'));
    this.maxAscent = Math.max(...textAscents);

    for (const textElement of svgTextElements) {
      const text = textElement.textContent ?? '';

      const foreign = document.createElementNS('http://www.w3.org/2000/svg', 'foreignObject');
      const replaceableHost = document.createElement(GENERIC_FRAGMENT_HOST);
      foreign.appendChild(replaceableHost);

      const nodeId = getNodeIdFromElement(textElement.parentElement!);

      const comp = this.componentFactory.create(this.injector, [], replaceableHost);
      comp.instance.nodeId = nodeId;
      comp.instance.printingType = printingType;
      comp.instance.text = text;
      comp.instance.color = textElement.getAttribute('fill')!;

      this.applicationRef.attachView(comp.hostView);

      const originalYAttr = textElement.getAttribute('y')!;
      foreign.dataset[ORIGINAL_Y] = originalYAttr;
      foreign.removeAttribute('y');

      this.setForeignYPosition(foreign, originalYAttr);

      foreign.setAttribute('x', textElement.getAttribute('x')!);
      foreign.setAttribute('width', '100%');
      foreign.setAttribute('height', '100%');

      foreign.style.pointerEvents = 'none';
      foreign.classList.add('transition-transform');

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
