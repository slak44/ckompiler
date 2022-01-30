import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveCompileComponent } from './components/live-compile/live-compile.component';
import { DiagnosticListComponent } from './components/diagnostic-list/diagnostic-list.component';
import { SourceEditorComponent } from './components/source-editor/source-editor.component';
import { LiveCompileRoutingModule } from './live-compile-routing.module';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MonacoEditorModule } from 'ng-monaco-editor';
import { MatTabsModule } from '@angular/material/tabs';
import { TabRoutingModule } from '../tab-routing/tab-routing.module';
import { MatChipsModule } from '@angular/material/chips';
import { DefaultGraphViewModule } from '../default-graph-view/default-graph-view.module';
import { PhiGraphViewModule } from '../phi-graph-view/phi-graph-view.module';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';

@NgModule({
  declarations: [
    LiveCompileComponent,
    SourceEditorComponent,
    DiagnosticListComponent,
  ],
  imports: [
    CommonModule,
    LiveCompileRoutingModule,
    PhiGraphViewModule,
    DefaultGraphViewModule,
    TabRoutingModule,
    MonacoEditorModule,
    ReactiveFormsModule,
    MatIconModule,
    MatToolbarModule,
    MatTabsModule,
    MatChipsModule,
    MatButtonModule,
  ],
})
export class LiveCompileModule {
}
