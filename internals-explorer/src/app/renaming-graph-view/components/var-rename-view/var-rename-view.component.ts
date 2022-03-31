import { ChangeDetectionStrategy, Component, HostListener } from '@angular/core';
import { RenamingIrFragmentComponent } from '../renaming-ir-fragment/renaming-ir-fragment.component';
import { Observable, of } from 'rxjs';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { RenamingStateService } from '../../services/renaming-state.service';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { AlgorithmPhase, AlgorithmStepService } from '../../../algorithm-stepper/services/algorithm-step.service';

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

  public readonly hooks: GraphViewHook[] = [
    new DisableDblClick(),
    removeHoverTitles,
    this.replaceNodeContentsHook,
  ];

  constructor(
    private algorithmStepService: AlgorithmStepService,
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
    private renamingStateService: RenamingStateService,
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