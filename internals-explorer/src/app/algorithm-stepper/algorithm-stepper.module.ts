import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AlgorithmStepComponent } from './components/algorithm-step/algorithm-step.component';
import { VarComponent } from './components/var/var.component';
import { AlgorithmContainerComponent } from './components/algorithm-container/algorithm-container.component';

@NgModule({
  declarations: [
    AlgorithmStepComponent,
    VarComponent,
    AlgorithmContainerComponent,
  ],
  exports: [
    AlgorithmStepComponent,
    VarComponent,
    AlgorithmContainerComponent,
  ],
  imports: [
    CommonModule,
  ],
})
export class AlgorithmStepperModule {
}
