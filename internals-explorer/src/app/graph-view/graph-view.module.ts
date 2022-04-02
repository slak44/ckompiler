import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewComponent } from './components/graph-view/graph-view.component';
import { ResizeObserverModule } from '@ng-web-apis/resize-observer';
import { SelectVariableComponent } from './components/select-variable/select-variable.component';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ReactiveFormsModule } from '@angular/forms';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { SelectFunctionComponent } from './components/select-function/select-function.component';

@NgModule({
  declarations: [
    GraphViewComponent,
    SelectVariableComponent,
    SelectFunctionComponent,
  ],
  imports: [
    CommonModule,
    ResizeObserverModule,
    MatFormFieldModule,
    MatSelectModule,
    ReactiveFormsModule,
    MatTooltipModule,
    MatIconModule,
    MatButtonModule,
  ],
  exports: [
    GraphViewComponent,
    SelectVariableComponent,
    SelectFunctionComponent,
  ],
})
export class GraphViewModule {
}
