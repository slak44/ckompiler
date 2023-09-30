import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AlgorithmStepComponent } from './components/algorithm-step/algorithm-step.component';
import { VarComponent } from './components/var/var.component';
import { AlgorithmContainerComponent } from './components/algorithm-container/algorithm-container.component';
import { StepperControlsComponent } from './components/stepper-controls/stepper-controls.component';
import { MatLegacyButtonModule as MatButtonModule } from '@angular/material/legacy-button';
import { MatLegacyTooltipModule as MatTooltipModule } from '@angular/material/legacy-tooltip';
import { MatIconModule } from '@angular/material/icon';
import { MatLegacySliderModule as MatSliderModule } from '@angular/material/legacy-slider';

@NgModule({
  declarations: [
    AlgorithmStepComponent,
    VarComponent,
    AlgorithmContainerComponent,
    StepperControlsComponent,
  ],
  exports: [
    AlgorithmStepComponent,
    VarComponent,
    AlgorithmContainerComponent,
    StepperControlsComponent,
  ],
  imports: [
    CommonModule,
    MatButtonModule,
    MatTooltipModule,
    MatIconModule,
    MatSliderModule,
  ],
})
export class AlgorithmStepperModule {
}
