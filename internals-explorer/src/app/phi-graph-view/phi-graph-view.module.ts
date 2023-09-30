import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PhiInsertionViewComponent } from './components/phi-insertion-view/phi-insertion-view.component';
import { PhiIrFragmentComponent } from './components/phi-ir-fragment/phi-ir-fragment.component';
import { ReactiveFormsModule } from '@angular/forms';
import { MatLegacyFormFieldModule as MatFormFieldModule } from '@angular/material/legacy-form-field';
import { MatLegacySelectModule as MatSelectModule } from '@angular/material/legacy-select';
import { GraphViewModule } from '@cki-graph-view/graph-view.module';
import { MatLegacyTooltipModule as MatTooltipModule } from '@angular/material/legacy-tooltip';
import { MatIconModule } from '@angular/material/icon';
import { MatLegacyButtonModule as MatButtonModule } from '@angular/material/legacy-button';
import { InsertionAlgorithmComponent } from './components/insertion-algorithm/insertion-algorithm.component';
import { MatLegacySliderModule as MatSliderModule } from '@angular/material/legacy-slider';
import { AlgorithmStepperModule } from '../algorithm-stepper/algorithm-stepper.module';

@NgModule({
  declarations: [
    PhiInsertionViewComponent,
    PhiIrFragmentComponent,
    InsertionAlgorithmComponent,
  ],
  imports: [
    CommonModule,
    GraphViewModule,
    AlgorithmStepperModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatSelectModule,
    MatTooltipModule,
    MatIconModule,
    MatButtonModule,
    MatSliderModule,
  ],
  exports: [
    PhiInsertionViewComponent,
  ],
})
export class PhiGraphViewModule {
}
