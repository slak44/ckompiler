import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { VarRenameViewComponent } from './components/var-rename-view/var-rename-view.component';
import { RenamingIrFragmentComponent } from './components/renaming-ir-fragment/renaming-ir-fragment.component';
import { GraphViewModule } from '@cki-graph-view/graph-view.module';



@NgModule({
  declarations: [
    VarRenameViewComponent,
    RenamingIrFragmentComponent,
  ],
  exports: [
    VarRenameViewComponent,
  ],
  imports: [
    CommonModule,
    GraphViewModule,
  ],
})
export class RenamingGraphViewModule { }
