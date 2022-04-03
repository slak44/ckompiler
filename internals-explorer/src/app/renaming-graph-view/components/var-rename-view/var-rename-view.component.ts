import { ChangeDetectionStrategy, Component, HostListener } from '@angular/core';
import { RenamingIrFragmentComponent } from '../renaming-ir-fragment/renaming-ir-fragment.component';
import { combineLatest, filter, map, merge, Observable, of } from 'rxjs';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { RenamingStateService } from '../../services/renaming-state.service';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { AlgorithmPhase, AlgorithmStepService } from '../../../algorithm-stepper/services/algorithm-step.service';
import { PanToSelected } from '@cki-graph-view/graph-view-hooks/pan-to-selected';
import { RenamingStep } from '../../models/renaming-step.model';
import { getNodeById } from '@cki-graph-view/utils';
import { slak } from '@ckompiler/ckompiler';
import arrayOf = slak.ckompiler.arrayOf;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;

@Component({
  selector: 'cki-var-rename-view',
  templateUrl: './var-rename-view.component.html',
  styleUrls: ['./var-rename-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    RenamingIrFragmentComponent.provider,
    AlgorithmStepService,
    ReplaceNodeContentsHook,
    RenamingStateService,
  ],
})
export class VarRenameViewComponent {
  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly instance: CompilationInstance = this.renamingStateService.compilationInstance;

  public readonly phase$: Observable<AlgorithmPhase> = this.algorithmStepService.phase$;

  public readonly algorithmPhase = AlgorithmPhase;

  public readonly stepCount$: Observable<number> = this.renamingStateService.stepCount$;

  public readonly variableName$: Observable<string> = this.renamingStateService.varState.variableName$;

  public readonly blockBB$: Observable<number | undefined> = this.renamingStateService.currentStepState$.pipe(
    map(state => state.bb),
  );

  public readonly bbSuccessorList$: Observable<number[] | undefined> = combineLatest([
    this.renamingStateService.currentStepState$,
    this.renamingStateService.compilationInstance.cfg$,
  ]).pipe(
    map(([state, cfg]) => {
      if (![RenamingStep.EACH_SUCC_PHI, RenamingStep.SUCC_PHI_REPLACE_USE].includes(state.step)) {
        return undefined;
      }

      if (typeof state.bb !== 'number') {
        return undefined;
      }

      return arrayOf<BasicBlock>(getNodeById(cfg, state.bb).successors).map(succ => succ.nodeId);
    }),
  );

  public readonly succBB$: Observable<number | undefined> = this.renamingStateService.currentStepState$.pipe(
    map(state => state.succBB),
  );

  private readonly selectedBlockId$: Observable<number> = merge(this.blockBB$, this.succBB$).pipe(
    filter((block): block is number => typeof block === 'number'),
  );

  public readonly hooks: GraphViewHook[] = [
    new DisableDblClick(),
    removeHoverTitles,
    this.replaceNodeContentsHook,
    new PanToSelected(this.selectedBlockId$),
  ];

  constructor(
    private readonly algorithmStepService: AlgorithmStepService,
    private readonly replaceNodeContentsHook: ReplaceNodeContentsHook,
    private readonly renamingStateService: RenamingStateService,
  ) {
  }

  public selectedVariableChanged(identityId: number): void {
    this.renamingStateService.varState.selectedVariableChanged(identityId);
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
