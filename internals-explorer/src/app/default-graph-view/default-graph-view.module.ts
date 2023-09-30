import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewModule } from '../graph-view/graph-view.module';
import { GraphViewFragmentComponent } from './components/graph-view-fragment/graph-view-fragment.component';
import { GraphLegendComponent } from './components/graph-legend/graph-legend.component';
import { GraphOptionsComponent } from './components/graph-options/graph-options.component';
import { DefaultGraphViewComponent } from './components/default-graph-view/default-graph-view.component';
import { GraphUiOverlayComponent } from './components/graph-ui-overlay/graph-ui-overlay.component';
import { HighlightModule } from 'ngx-highlightjs';
import { MatLegacyCheckboxModule as MatCheckboxModule } from '@angular/material/legacy-checkbox';
import { ReactiveFormsModule } from '@angular/forms';
import { MatLegacyFormFieldModule as MatFormFieldModule } from '@angular/material/legacy-form-field';
import { MatLegacySelectModule as MatSelectModule } from '@angular/material/legacy-select';

@NgModule({
  declarations: [
    GraphViewFragmentComponent,
    GraphLegendComponent,
    GraphOptionsComponent,
    DefaultGraphViewComponent,
    GraphUiOverlayComponent,
  ],
  imports: [
    CommonModule,
    GraphViewModule,
    HighlightModule,
    MatCheckboxModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatSelectModule,
  ],
  exports: [
    DefaultGraphViewComponent,
  ],
})
export class DefaultGraphViewModule {
}
