import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PhiInsertionViewComponent } from './components/phi-insertion-view/phi-insertion-view.component';
import { PhiIrFragmentComponent } from './components/phi-ir-fragment/phi-ir-fragment.component';
import { ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { GraphViewModule } from '@cki-graph-view/graph-view.module';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { InsertionAlgorithmComponent } from './components/insertion-algorithm/insertion-algorithm.component';

@NgModule({
  declarations: [
    PhiInsertionViewComponent,
    PhiIrFragmentComponent,
    InsertionAlgorithmComponent,
  ],
  imports: [
    CommonModule,
    GraphViewModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatSelectModule,
    MatTooltipModule,
    MatIconModule,
    MatButtonModule,
  ],
  exports: [
    PhiInsertionViewComponent,
  ],
})
export class PhiGraphViewModule {
}