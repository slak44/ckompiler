import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { VarRenameViewComponent } from './components/var-rename-view/var-rename-view.component';



@NgModule({
  declarations: [
    VarRenameViewComponent,
  ],
  exports: [
    VarRenameViewComponent,
  ],
  imports: [
    CommonModule,
  ],
})
export class RenamingGraphViewModule { }
