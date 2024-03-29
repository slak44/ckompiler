import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, HostListener, ViewChild } from '@angular/core';
import { combineLatest, filter, map, merge, Observable, of, takeUntil } from 'rxjs';
import { arrayOfCollection, BasicBlock, CodePrintingMethods } from '@ckompiler/ckompiler';
import { PhiIrFragmentComponent } from '../phi-ir-fragment/phi-ir-fragment.component';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { getNodeById } from '@cki-graph-view/utils';
import { StartNodeRect } from '@cki-graph-view/graph-view-hooks/start-node-rect';
import { PanToSelected } from '@cki-graph-view/graph-view-hooks/pan-to-selected';
import { NodePath } from '@cki-graph-view/graph-view-hooks/node-path';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import {
  AlgorithmPhase,
  AlgorithmStepService,
  STEP_IDX_SETTING,
} from '../../../algorithm-stepper/services/algorithm-step.service';
import {
  phiInsertionSelectedId,
  phiInsertionStepIdx,
  phiInsertionTransform,
  phiInsertionVariableId,
} from '@cki-settings';
import { CommonModule } from '@angular/common';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { SelectFunctionComponent } from '@cki-graph-view/components/select-function/select-function.component';
import { SelectVariableComponent } from '@cki-graph-view/components/select-variable/select-variable.component';
import { VarComponent } from '../../../algorithm-stepper/components/var/var.component';
import {
  StepperControlsComponent
} from '../../../algorithm-stepper/components/stepper-controls/stepper-controls.component';
import { InsertionAlgorithmComponent } from '../insertion-algorithm/insertion-algorithm.component';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'cki-phi-insertion-view',
  templateUrl: './phi-insertion-view.component.html',
  styleUrls: ['./phi-insertion-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    PhiIrFragmentComponent.provider,
    { provide: STEP_IDX_SETTING, useValue: phiInsertionStepIdx },
    AlgorithmStepService,
    PhiInsertionStateService,
    ReplaceNodeContentsHook,
  ],
  standalone: true,
  imports: [
    CommonModule,
    GraphViewComponent,
    SelectFunctionComponent,
    SelectVariableComponent,
    VarComponent,
    StepperControlsComponent,
    InsertionAlgorithmComponent,
    MatButtonModule,
    MatIconModule,
  ],
})
export class PhiInsertionViewComponent extends SubscriptionDestroy implements AfterViewInit {
  @ViewChild('anchorStartBlock')
  private readonly anchorStartBlock!: ElementRef<HTMLDivElement>;

  public readonly phiInsertionTransform = phiInsertionTransform;
  public readonly phiInsertionSelectedId = phiInsertionSelectedId;
  public readonly phiInsertionVariableId = phiInsertionVariableId;

  private readonly startNodeRect: StartNodeRect = new StartNodeRect();

  public readonly printingType$: Observable<CodePrintingMethods> = of(CodePrintingMethods.IR_TO_STRING);

  public readonly instance: CompilationInstance = this.phiInsertionStateService.compilationInstance;
  public readonly phase$: Observable<AlgorithmPhase> = this.algorithmStepService.phase$;

  public readonly algorithmPhase = AlgorithmPhase;

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
      const df = arrayOfCollection<BasicBlock>(x.dominanceFrontier);

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
      this.algorithmStepService.phase$,
    ]).pipe(
      map(([state, phase]) => phase === AlgorithmPhase.RUNNING ? state.highlightedPhiPaths : undefined),
    );

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
    new NodePath(
      this.replaceNodeContents,
      this.phiInsertionStateService.varState.targetVariable$,
      this.highlightedPhiPaths$,
      true
    ),
  ];

  constructor(
    private readonly algorithmStepService: AlgorithmStepService,
    private readonly replaceNodeContents: ReplaceNodeContentsHook,
    private readonly phiInsertionStateService: PhiInsertionStateService,
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
    this.algorithmStepService.start();
  }

  public reset(): void {
    this.algorithmStepService.reset();
  }

  @HostListener('document:keydown.arrowright')
  public nextStep(): void {
    this.algorithmStepService.nextStep();
  }

  @HostListener('document:keydown.arrowleft')
  public prevStep(): void {
    this.algorithmStepService.prevStep();
  }

  @HostListener('document:keydown.home')
  public jumpToStart(): void {
    this.algorithmStepService.setStep(0);
  }

  @HostListener('document:keydown.end')
  public jumpToEnd(): void {
    this.algorithmStepService.setStep(Infinity);
  }
}
