import { ChangeDetectionStrategy, Component, HostListener } from '@angular/core';
import { RenamingIrFragmentComponent } from '../renaming-ir-fragment/renaming-ir-fragment.component';
import { combineLatest, filter, map, merge, Observable, of, pairwise, startWith } from 'rxjs';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { RenamingStateService } from '../../services/renaming-state.service';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import {
  AlgorithmPhase,
  AlgorithmStepService,
  STEP_IDX_SETTING,
} from '../../../algorithm-stepper/services/algorithm-step.service';
import { PanToSelected } from '@cki-graph-view/graph-view-hooks/pan-to-selected';
import { RenamingStep, RenamingStepState } from '../../models/renaming-step.model';
import { getNodeById } from '@cki-graph-view/utils';
import { arrayOfCollection, BasicBlock, CodePrintingMethods } from '@ckompiler/ckompiler';
import {
  variableRenameSelectedId,
  variableRenameStepIdx,
  variableRenameTransform,
  variableRenameVariableId,
} from '@cki-settings';
import { CommonModule } from '@angular/common';
import { GraphViewComponent } from '@cki-graph-view/components/graph-view/graph-view.component';
import { SelectFunctionComponent } from '@cki-graph-view/components/select-function/select-function.component';
import { SelectVariableComponent } from '@cki-graph-view/components/select-variable/select-variable.component';
import { VarComponent } from '../../../algorithm-stepper/components/var/var.component';
import { MatIconModule } from '@angular/material/icon';
import {
  StepperControlsComponent
} from '../../../algorithm-stepper/components/stepper-controls/stepper-controls.component';
import { RenameAlgorithmComponent } from '../rename-algorithm/rename-algorithm.component';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'cki-var-rename-view',
  templateUrl: './var-rename-view.component.html',
  styleUrls: ['./var-rename-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    RenamingIrFragmentComponent.provider,
    { provide: STEP_IDX_SETTING, useValue: variableRenameStepIdx },
    AlgorithmStepService,
    ReplaceNodeContentsHook,
    RenamingStateService,
  ],
  standalone: true,
  imports: [
    CommonModule,
    GraphViewComponent,
    SelectFunctionComponent,
    SelectVariableComponent,
    VarComponent,
    MatIconModule,
    StepperControlsComponent,
    RenameAlgorithmComponent,
    MatButtonModule,
  ],
})
export class VarRenameViewComponent {
  public readonly variableRenameTransform = variableRenameTransform;
  public readonly variableRenameSelectedId = variableRenameSelectedId;
  public readonly variableRenameVariableId = variableRenameVariableId;

  public readonly printingType$: Observable<CodePrintingMethods> = of(CodePrintingMethods.IR_TO_STRING);

  public readonly instance: CompilationInstance = this.renamingStateService.compilationInstance;

  public readonly phase$: Observable<AlgorithmPhase> = this.algorithmStepService.phase$;

  public readonly algorithmPhase = AlgorithmPhase;

  public readonly stepCount$: Observable<number> = this.renamingStateService.stepCount$;

  public readonly variableName$: Observable<string> = this.renamingStateService.varState.variableName$;

  public readonly currentStepState$: Observable<RenamingStepState> = this.renamingStateService.currentStepState$;

  public readonly reachingDefVersion$: Observable<number | undefined> = this.currentStepState$.pipe(
    map(state => state.newVersion),
    startWith(0),
  );

  public readonly latestVersion$: Observable<number> = this.reachingDefVersion$.pipe(
    filter((version): version is number => typeof version === 'number'),
    pairwise(),
    map(([oldVersion, newVersion]) => Math.max(oldVersion, newVersion)),
  );

  public readonly hasNewDefinition$: Observable<boolean> = this.currentStepState$.pipe(
    map(state => state.step === RenamingStep.INSTR_REPLACE_DEF),
  );

  public readonly blockBB$: Observable<number | undefined> = this.currentStepState$.pipe(
    map(state => state.bb),
  );

  public readonly bbSuccessorList$: Observable<number[] | undefined> = combineLatest([
    this.currentStepState$,
    this.renamingStateService.compilationInstance.cfg$,
  ]).pipe(
    map(([state, cfg]) => {
      if (![RenamingStep.EACH_SUCC_PHI, RenamingStep.SUCC_PHI_REPLACE_USE].includes(state.step)) {
        return undefined;
      }

      if (typeof state.bb !== 'number') {
        return undefined;
      }

      return arrayOfCollection<BasicBlock>(getNodeById(cfg, state.bb).successors).map(succ => succ.nodeId);
    }),
  );

  public readonly succBB$: Observable<number | undefined> = this.currentStepState$.pipe(
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
