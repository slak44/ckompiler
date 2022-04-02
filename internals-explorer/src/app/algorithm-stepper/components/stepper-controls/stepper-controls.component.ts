import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { AlgorithmStepService } from '../../services/algorithm-step.service';
import { MatSliderChange } from '@angular/material/slider';
import { Observable } from 'rxjs';

@Component({
  selector: 'cki-stepper-controls',
  templateUrl: './stepper-controls.component.html',
  styleUrls: ['./stepper-controls.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StepperControlsComponent {
  @Input()
  public stepCount$!: Observable<number>;

  public readonly currentStep$: Observable<number> = this.algorithmStepService.currentStep$;

  constructor(
    private readonly algorithmStepService: AlgorithmStepService,
  ) {
  }

  public currentStepSliderChange(sliderChange: MatSliderChange): void {
    this.algorithmStepService.setStep((sliderChange.value ?? 0) - 1);
  }

  public prevStep(): void {
    this.algorithmStepService.prevStep();
  }

  public nextStep(): void {
    this.algorithmStepService.nextStep();
  }
}
