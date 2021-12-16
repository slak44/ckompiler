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
import { MonacoEditorModule } from 'ngx-monaco-editor';
import { ReactiveFormsModule } from '@angular/forms';

@NgModule({
  declarations: [
    GraphViewComponent,
    IrFragmentComponent,
    LiveCompileComponent,
    SourceEditorComponent,
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
  ],
})
export class GraphViewModule {
}
