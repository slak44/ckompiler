import { ChangeDetectionStrategy, Component } from '@angular/core';
import { map, Observable } from 'rxjs';
import { PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { phaseInOut } from '@cki-utils/phase-in-out';
import { PhiInsertionStep } from '../../models/phi-insertion-steps.model';

@Component({
  selector: 'cki-insertion-algorithm',
  templateUrl: './insertion-algorithm.component.html',
  styleUrls: ['./insertion-algorithm.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [phaseInOut],
})
export class InsertionAlgorithmComponent {
  public readonly variableName$: Observable<string> = this.phiInsertionStateService.varState.targetVariable$.pipe(
    map(variable => variable.name),
  );

  public readonly activeStep$: Observable<PhiInsertionStep> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.step),
  );

  public readonly phiInsertionSteps = PhiInsertionStep;

  constructor(
    private phiInsertionStateService: PhiInsertionStateService,
  ) {
  }
}
