import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AlgorithmContainerDirective } from './directives/algorithm-container.directive';
import { AlgorithmStepComponent } from './components/algorithm-step/algorithm-step.component';
import { VarComponent } from './components/var/var.component';

@NgModule({
  declarations: [
    AlgorithmStepComponent,
    AlgorithmContainerDirective,
    VarComponent,
  ],
  exports: [
    AlgorithmStepComponent,
    AlgorithmContainerDirective,
    VarComponent,
  ],
  imports: [
    CommonModule,
  ],
})
export class AlgorithmStepperModule {
}
