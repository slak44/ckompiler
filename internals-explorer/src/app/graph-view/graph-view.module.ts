import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewRoutingModule } from './graph-view-routing.module';
import { GraphViewComponent } from './components/graph-view/graph-view.component';
import { HighlightModule } from 'ngx-highlightjs';
import { GraphViewFragmentComponent } from './components/graph-view-fragment/graph-view-fragment.component';
import { LiveCompileComponent } from './components/live-compile/live-compile.component';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTabsModule } from '@angular/material/tabs';
import { ResizeObserverModule } from '@ng-web-apis/resize-observer';
import { SourceEditorComponent } from './components/source-editor/source-editor.component';
import { ReactiveFormsModule } from '@angular/forms';
import { DiagnosticListComponent } from './components/diagnostic-list/diagnostic-list.component';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { GraphLegendComponent } from './components/graph-legend/graph-legend.component';
import { MonacoEditorModule } from 'ng-monaco-editor';
import { GraphOptionsComponent } from './components/graph-options/graph-options.component';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { TabRoutingModule } from '../tab-routing/tab-routing.module';
import { DefaultGraphViewComponent } from './components/default-graph-view/default-graph-view.component';
import { GraphUiOverlayComponent } from './components/graph-ui-overlay/graph-ui-overlay.component';
import { PhiInsertionViewComponent } from './components/phi-insertion-view/phi-insertion-view.component';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PhiIrFragmentComponent } from './components/phi-ir-fragment/phi-ir-fragment.component';

@NgModule({
  declarations: [
    GraphViewComponent,
    GraphViewFragmentComponent,
    LiveCompileComponent,
    SourceEditorComponent,
    DiagnosticListComponent,
    GraphLegendComponent,
    GraphOptionsComponent,
    DefaultGraphViewComponent,
    GraphUiOverlayComponent,
    PhiInsertionViewComponent,
    PhiIrFragmentComponent,
  ],
  imports: [
    CommonModule,
    HighlightModule,
    GraphViewRoutingModule,
    MatToolbarModule,
    MatIconModule,
    MatButtonModule,
    MatTabsModule,
    ResizeObserverModule,
    MonacoEditorModule,
    ReactiveFormsModule,
    MatChipsModule,
    MatFormFieldModule,
    MatSelectModule,
    MatCheckboxModule,
    TabRoutingModule,
    MatTooltipModule,
  ],
})
export class GraphViewModule {
}
