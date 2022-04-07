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
import {
  FRAGMENT_COMPONENT,
  FragmentComponent,
  FragmentSource,
  GENERIC_FRAGMENT_HOST,
} from '../models/fragment-component.model';
import { Observable } from 'rxjs';
import { measureTextAscent } from '@cki-utils/measure-text';
import { getNodeById } from '@cki-graph-view/utils';
import CFG = slak.ckompiler.analysis.CFG;
import arrayOfIterator = slak.ckompiler.arrayOfIterator;
import IRInstruction = slak.ckompiler.analysis.IRInstruction;
import arrayOf = slak.ckompiler.arrayOf;
import PhiInstruction = slak.ckompiler.analysis.PhiInstruction;
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

const ORIGINAL_Y = 'originalY';

@Injectable()
export class ReplaceNodeContentsHook implements GraphViewHook {
  private readonly foreignToTextMap: Map<SVGForeignObjectElement, SVGTextElement> = new Map();

  private readonly componentFactory: ComponentFactory<FragmentComponent> =
    this.componentFactoryResolver.resolveComponentFactory(this.fragmentComponentType);

  private graphView!: GraphViewComponent;
  private maxAscent!: number;

  public rerender$!: Observable<void>;

  constructor(
    private readonly componentFactoryResolver: ComponentFactoryResolver,
    private readonly injector: Injector,
    private readonly applicationRef: ApplicationRef,
    @Inject(FRAGMENT_COMPONENT) private readonly fragmentComponentType: Type<FragmentComponent>,
  ) {
  }

  public getMaxAscent(): number {
    return this.maxAscent;
  }

  private setForeignYPosition(foreignObject: SVGForeignObjectElement, yPosAttr: string | number): void {
    const yPos = +yPosAttr;
    foreignObject.setAttribute('transform', `translate(0, ${-this.maxAscent + yPos})`);
  }

  public reLayoutNodeFragments(nodeId: number): void {
    const node = this.graphView.getGroupByNodeId(nodeId);
    const texts = Array.from(node.querySelectorAll('foreignObject'));

    if (texts.length === 0) {
      return;
    }

    const visibleObjects = texts.filter(
      foreignObject => !foreignObject.firstElementChild!.classList.contains('hidden-fragment'),
    );

    const poly = node.querySelector('polygon')!;
    const polyHeight = poly.getBBox().height;
    const polyStrokeHeight = 7; // determined empirically

    const topY = parseFloat(texts[0].dataset[ORIGINAL_Y]!);
    const secondTopY = parseFloat(texts?.[1]?.dataset?.[ORIGINAL_Y] || '') || polyHeight;

    const elementHeight = secondTopY - topY;
    const emptySpace = polyHeight - polyStrokeHeight - elementHeight * visibleObjects.length;

    let currentOffset = topY + emptySpace / 2;
    for (const foreignObject of visibleObjects) {
      this.setForeignYPosition(foreignObject, currentOffset);
      currentOffset += elementHeight;
    }
  }

  public alterGraph(graphView: GraphViewComponent, cfg: CFG, printingType: CodePrintingMethods, graph: Element): void {
    this.graphView = graphView;
    this.rerender$ = graphView.rerender$;

    this.maxAscent = -Infinity;

    for (const node of Array.from(graph.querySelectorAll('g.node'))) {
      const textNodes = node.querySelectorAll('text');
      const nodeId = graphView.getNodeIdByGroup(node as SVGGElement);
      const basicBlock = getNodeById(cfg, nodeId);

      const instructions = arrayOfIterator<IRInstruction>(basicBlock.instructions);
      const phis = arrayOf<PhiInstruction>(basicBlock.phi);

      const sources: FragmentSource[] = [...phis, ...instructions];

      const offset = textNodes.length - sources.length;

      for (let i = 0; i < textNodes.length; i++) {
        const textElement = textNodes[i];

        // Assume offset is always at the start
        const source = i < offset ? undefined : sources[i - offset];

        const text = textElement.textContent ?? '';

        this.maxAscent = Math.max(this.maxAscent, measureTextAscent(text));

        const foreign = document.createElementNS('http://www.w3.org/2000/svg', 'foreignObject');
        const replaceableHost = document.createElement(GENERIC_FRAGMENT_HOST);
        foreign.appendChild(replaceableHost);

        const comp = this.componentFactory.create(this.injector, [], replaceableHost);
        comp.instance.nodeId = nodeId;
        comp.instance.i = i - offset;
        comp.instance.instr = source;
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
  }

  public revertAlteration(): void {
    for (const [foreign, text] of this.foreignToTextMap) {
      foreign.replaceWith(text);
    }
    this.foreignToTextMap.clear();
  }
}
