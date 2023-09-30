import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewComponent } from './components/graph-view/graph-view.component';
import { ResizeObserverModule } from '@ng-web-apis/resize-observer';
import { SelectVariableComponent } from './components/select-variable/select-variable.component';
import { MatLegacyFormFieldModule as MatFormFieldModule } from '@angular/material/legacy-form-field';
import { MatLegacySelectModule as MatSelectModule } from '@angular/material/legacy-select';
import { ReactiveFormsModule } from '@angular/forms';
import { MatLegacyTooltipModule as MatTooltipModule } from '@angular/material/legacy-tooltip';
import { MatIconModule } from '@angular/material/icon';
import { MatLegacyButtonModule as MatButtonModule } from '@angular/material/legacy-button';
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
