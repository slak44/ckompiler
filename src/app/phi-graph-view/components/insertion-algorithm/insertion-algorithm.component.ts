import { ChangeDetectionStrategy, Component } from '@angular/core';
import { map, Observable } from 'rxjs';
import { PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { PhiInsertionStep } from '../../models/phi-insertion-steps.model';
import { CommonModule } from '@angular/common';
import {
  AlgorithmContainerComponent
} from '../../../algorithm-stepper/components/algorithm-container/algorithm-container.component';
import { AlgorithmStepComponent } from '../../../algorithm-stepper/components/algorithm-step/algorithm-step.component';
import { VarComponent } from '../../../algorithm-stepper/components/var/var.component';

@Component({
  selector: 'cki-insertion-algorithm',
  templateUrl: './insertion-algorithm.component.html',
  styleUrls: ['./insertion-algorithm.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    AlgorithmContainerComponent,
    AlgorithmStepComponent,
    VarComponent,
  ],
})
export class InsertionAlgorithmComponent {
  public readonly variableName$: Observable<string> = this.phiInsertionStateService.varState.variableName$;

  public readonly activeStep$: Observable<PhiInsertionStep> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.step),
  );

  public readonly phiInsertionSteps = PhiInsertionStep;

  constructor(
    private readonly phiInsertionStateService: PhiInsertionStateService,
  ) {
  }
}
