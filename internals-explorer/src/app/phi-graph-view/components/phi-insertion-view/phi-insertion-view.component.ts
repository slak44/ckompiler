import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, HostListener, ViewChild } from '@angular/core';
import { combineLatest, filter, map, Observable, of, takeUntil } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { PhiIrFragmentComponent } from '../phi-ir-fragment/phi-ir-fragment.component';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { PhiInsertionState, PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { getNodeById } from '@cki-graph-view/utils';
import { MatSliderChange } from '@angular/material/slider';
import { StartNodeRect } from '@cki-graph-view/graph-view-hooks/start-node-rect';
import Variable = slak.ckompiler.analysis.Variable;
import JSCompileResult = slak.ckompiler.JSCompileResult;
import arrayOf = slak.ckompiler.arrayOf;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import { PhiInsertionTourService } from '../../services/phi-insertion-tour.service';

@Component({
  selector: 'cki-phi-insertion-view',
  templateUrl: './phi-insertion-view.component.html',
  styleUrls: ['./phi-insertion-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    PhiIrFragmentComponent.provider,
    PhiInsertionStateService,
    PhiInsertionTourService,
    ReplaceNodeContentsHook
  ],
})
export class PhiInsertionViewComponent extends SubscriptionDestroy implements AfterViewInit {
  @ViewChild('anchorStartBlock')
  private readonly anchorStartBlock!: ElementRef<HTMLDivElement>;

  private readonly startNodeRect: StartNodeRect = new StartNodeRect();

  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly hooks: GraphViewHook[] = [
    removeHoverTitles,
    new DisableDblClick(),
    this.replaceNodeContents,
    this.startNodeRect,
  ];

  public readonly compileResult$: Observable<JSCompileResult> = this.phiInsertionStateService.compileResult$;
  public readonly variables$: Observable<Variable[]> = this.phiInsertionStateService.variables$;
  public readonly phiInsertionState$: Observable<PhiInsertionState> = this.phiInsertionStateService.phiInsertionState$;

  public readonly phiInsertionStates = PhiInsertionState;

  public readonly variableControl: FormControl = new FormControl(null);

  public readonly variableId$: Observable<number> = controlValueStream<number | null>(this.variableControl).pipe(
    filter((identityId): identityId is number => typeof identityId === 'number'),
  );

  public readonly worklist$: Observable<string> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.w.join(', ')),
  );

  public readonly processed$: Observable<string> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.f.join(', ')),
  );

  public readonly dominanceFrontierX$: Observable<string | null> = combineLatest([
    this.phiInsertionStateService.currentStepState$,
    this.phiInsertionStateService.cfg$,
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

  public readonly currentStep$: Observable<number> = this.phiInsertionStateService.currentStep$;
  public readonly insertionStepCount$: Observable<number> = this.phiInsertionStateService.insertionStepCount$;

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private phiInsertionStateService: PhiInsertionStateService,
    private phiInsertionTourService: PhiInsertionTourService,
  ) {
    super();

    this.variableId$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(identityId => {
      this.phiInsertionStateService.selectedVariableChanged(identityId);
    });

    this.phiInsertionTourService.start();
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
