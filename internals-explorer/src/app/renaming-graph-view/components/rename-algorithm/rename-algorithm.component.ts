import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RenamingStep } from '../../models/renaming-step.model';
import { map, Observable } from 'rxjs';
import { RenamingStateService } from '../../services/renaming-state.service';

@Component({
  selector: 'cki-rename-algorithm',
  templateUrl: './rename-algorithm.component.html',
  styleUrls: ['./rename-algorithm.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RenameAlgorithmComponent {
  public readonly renamingSteps = RenamingStep;

  public readonly activeStep$: Observable<RenamingStep> = this.renamingStateService.currentStepState$.pipe(
    map(state => state.step),
  );

  public readonly variableName$: Observable<string> = this.renamingStateService.varState.variableName$;

  constructor(
    private readonly renamingStateService: RenamingStateService,
  ) {
  }
}
