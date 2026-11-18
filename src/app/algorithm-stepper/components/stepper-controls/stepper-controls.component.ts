import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { AlgorithmStepService } from '../../services/algorithm-step.service';
import { Observable } from 'rxjs';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';

@Component({
  selector: 'cki-stepper-controls',
  templateUrl: './stepper-controls.component.html',
  styleUrls: ['./stepper-controls.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatTooltipModule,
    MatIconModule,
    MatSliderModule,
  ],
})
export class StepperControlsComponent {
  @Input()
  public stepCount$!: Observable<number>;

  public readonly currentStep$: Observable<number> = this.algorithmStepService.currentStep$;

  constructor(
    private readonly algorithmStepService: AlgorithmStepService,
  ) {
  }

  public currentStepSliderChange(value: number | null): void {
    this.algorithmStepService.setStep((value ?? 0) - 1);
  }

  public prevStep(): void {
    this.algorithmStepService.prevStep();
  }

  public nextStep(): void {
    this.algorithmStepService.nextStep();
  }
}
