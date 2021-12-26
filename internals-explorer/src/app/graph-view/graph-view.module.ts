import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewRoutingModule } from './graph-view-routing.module';
import { GraphViewComponent } from './components/graph-view/graph-view.component';
import { HighlightModule } from 'ngx-highlightjs';
import { IrFragmentComponent } from './components/ir-fragment/ir-fragment.component';
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
import { monacoLoader } from '@cki-utils/monaco-loader';
import { GraphOptionsComponent } from './components/graph-options/graph-options.component';

@NgModule({
  declarations: [
    GraphViewComponent,
    IrFragmentComponent,
    LiveCompileComponent,
    SourceEditorComponent,
    DiagnosticListComponent,
    GraphLegendComponent,
    GraphOptionsComponent,
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
  ],
})
export class GraphViewModule {
}
