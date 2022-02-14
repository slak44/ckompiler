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

  private graph: Element | undefined;

  private maxAscent!: number;

  public rerender$!: Observable<void>;

  constructor(
    private componentFactoryResolver: ComponentFactoryResolver,
    private injector: Injector,
    private applicationRef: ApplicationRef,
    @Inject(FRAGMENT_COMPONENT) private fragmentComponentType: Type<FragmentComponent>,
  ) {
  }

  private setForeignYPosition(foreignObject: SVGForeignObjectElement, yPosAttr: string): void {
    const yPos = parseInt(yPosAttr, 10);
    foreignObject.setAttribute('transform', `translate(0, ${-this.maxAscent + yPos})`);
  }

  public reLayoutNodeFragments(nodeId: number): void {
    const node = this.graph!.parentNode!.querySelector(`#node${nodeId}`)!;
    const texts = Array.from(node.querySelectorAll('foreignObject'));

    const yPositions = texts.map(text => text.dataset[ORIGINAL_Y]!);

    let currentYIndex = 0;
    for (const foreignObject of texts) {
      foreignObject.classList.add('transition-transform');
      const fragmentComponent = foreignObject.firstElementChild!;
      if (fragmentComponent.classList.contains('hidden-fragment')) {
        foreignObject.style.display = 'none';
      } else {
        this.setForeignYPosition(foreignObject, yPositions[currentYIndex++]);
      }
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
