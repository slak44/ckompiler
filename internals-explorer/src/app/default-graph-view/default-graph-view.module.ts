import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewModule } from '../graph-view/graph-view.module';
import { GraphViewFragmentComponent } from './components/graph-view-fragment/graph-view-fragment.component';
import { GraphLegendComponent } from './components/graph-legend/graph-legend.component';
import { GraphOptionsComponent } from './components/graph-options/graph-options.component';
import { DefaultGraphViewComponent } from './components/default-graph-view/default-graph-view.component';
import { GraphUiOverlayComponent } from './components/graph-ui-overlay/graph-ui-overlay.component';
import { HighlightModule } from 'ngx-highlightjs';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';

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
