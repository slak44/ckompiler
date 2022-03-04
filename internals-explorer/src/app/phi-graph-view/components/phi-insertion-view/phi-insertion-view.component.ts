import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, HostListener, ViewChild } from '@angular/core';
import { combineLatest, filter, map, merge, Observable, of, takeUntil } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { PhiIrFragmentComponent } from '../phi-ir-fragment/phi-ir-fragment.component';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { PhiInsertionPhase, PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { getNodeById } from '@cki-graph-view/utils';
import { MatSliderChange } from '@angular/material/slider';
import { StartNodeRect } from '@cki-graph-view/graph-view-hooks/start-node-rect';
import { PanToSelected } from '@cki-graph-view/graph-view-hooks/pan-to-selected';
import { NodePath } from '@cki-graph-view/graph-view-hooks/node-path';
import Variable = slak.ckompiler.analysis.Variable;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import arrayOf = slak.ckompiler.arrayOf;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;

@Component({
  selector: 'cki-phi-insertion-view',
  templateUrl: './phi-insertion-view.component.html',
  styleUrls: ['./phi-insertion-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    PhiIrFragmentComponent.provider,
    PhiInsertionStateService,
    ReplaceNodeContentsHook,
  ],
})
export class PhiInsertionViewComponent extends SubscriptionDestroy implements AfterViewInit {
  @ViewChild('anchorStartBlock')
  private readonly anchorStartBlock!: ElementRef<HTMLDivElement>;

  private readonly startNodeRect: StartNodeRect = new StartNodeRect();

  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly compileResult$: Observable<JSCompileResult> = this.phiInsertionStateService.compileResult$;
  public readonly variables$: Observable<Variable[]> = this.phiInsertionStateService.compilationInstance.variables$;
  public readonly phiInsertionPhase$: Observable<PhiInsertionPhase> = this.phiInsertionStateService.phiInsertionPhase$;

  public readonly phiInsertionPhases = PhiInsertionPhase;

  public readonly worklist$: Observable<string> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.w.join(', ')),
  );

  public readonly processed$: Observable<string> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.f.join(', ')),
  );

  public readonly blockX$: Observable<number | undefined> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.blockX),
  );

  public readonly dominanceFrontierX$: Observable<string | null> = combineLatest([
    this.phiInsertionStateService.currentStepState$,
    this.phiInsertionStateService.compilationInstance.cfg$,
  ]).pipe(
    map(([state, cfg]) => {
      if (typeof state.blockX !== 'number') {
        return null;
      }

      const x = getNodeById(cfg, state.blockX);
      const df = arrayOf<BasicBlock>(x.dominanceFrontier);

      if (df.length === 0) {
        return ' ';
      }

      return df.map(block => block.nodeId).join(', ');
    }),
  );

  public readonly blockY$: Observable<number | undefined> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.blockY),
  );

  public readonly highlightedPhiPaths$: Observable<number[][] | undefined> =
    combineLatest([
      this.phiInsertionStateService.currentStepState$,
      this.phiInsertionStateService.phiInsertionPhase$,
    ]).pipe(
      map(([state, phase]) => phase === PhiInsertionPhase.WORKLOOP ? state.highlightedPhiPaths : undefined),
    );

  public readonly currentStep$: Observable<number> = this.phiInsertionStateService.currentStep$;
  public readonly insertionStepCount$: Observable<number> = this.phiInsertionStateService.insertionStepCount$;

  private readonly selectedNodeId$: Observable<number> = merge(this.blockX$, this.blockY$).pipe(
    filter((block): block is number => typeof block === 'number'),
  );

  public readonly hooks: GraphViewHook[] = [
    removeHoverTitles,
    new DisableDblClick(),
    this.replaceNodeContents,
    this.startNodeRect,
    new PanToSelected(this.selectedNodeId$),
    new NodePath(this.replaceNodeContents, this.phiInsertionStateService.targetVariable$, this.highlightedPhiPaths$),
  ];

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private phiInsertionStateService: PhiInsertionStateService,
  ) {
    super();
  }

  public ngAfterViewInit(): void {
    this.startNodeRect.position$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(({ graph, element }) => {
      const top = element.top - graph.top;
      this.anchorStartBlock.nativeElement.style.top = `${top}px`;
      this.anchorStartBlock.nativeElement.style.left = `${element.right}px`;
    });
  }

  public start(): void {
    this.phiInsertionStateService.startInsertion();
  }

  public reset(): void {
    this.phiInsertionStateService.reset();
  }

  public selectedVariableChanged(identityId: number): void {
    this.phiInsertionStateService.selectedVariableChanged(identityId);
  }

  public currentStepSliderChange(sliderChange: MatSliderChange): void {
    this.phiInsertionStateService.setStep((sliderChange.value ?? 0) - 1);
  }

  @HostListener('document:keydown.arrowright')
  public nextStep(): void {
    this.phiInsertionStateService.nextStep();
  }

  @HostListener('document:keydown.arrowleft')
  public prevStep(): void {
    this.phiInsertionStateService.prevStep();
  }

  @HostListener('document:keydown.home')
  public jumpToStart(): void {
    this.phiInsertionStateService.setStep(0);
  }

  @HostListener('document:keydown.end')
  public jumpToEnd(): void {
    this.phiInsertionStateService.setStep(Infinity);
  }
}
