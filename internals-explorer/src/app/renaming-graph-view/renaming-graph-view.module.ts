import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { VarRenameViewComponent } from './components/var-rename-view/var-rename-view.component';
import { RenamingIrFragmentComponent } from './components/renaming-ir-fragment/renaming-ir-fragment.component';
import { GraphViewModule } from '@cki-graph-view/graph-view.module';
import { RenameAlgorithmComponent } from './components/rename-algorithm/rename-algorithm.component';
import { AlgorithmStepperModule } from '../algorithm-stepper/algorithm-stepper.module';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';



@NgModule({
  declarations: [
    VarRenameViewComponent,
    RenamingIrFragmentComponent,
    RenameAlgorithmComponent,
  ],
  exports: [
    VarRenameViewComponent,
  ],
  imports: [
    CommonModule,
    GraphViewModule,
    AlgorithmStepperModule,
    MatIconModule,
    MatButtonModule,
  ],
})
export class RenamingGraphViewModule { }
