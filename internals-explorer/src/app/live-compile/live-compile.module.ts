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
import { RenamingGraphViewModule } from '../renaming-graph-view/renaming-graph-view.module';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialogModule } from '@angular/material/dialog';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { SettingsModule } from '../settings/settings.module';
import { MatSelectModule } from '@angular/material/select';
import { MatSidenavModule } from '@angular/material/sidenav';
import { SidenavContentComponent } from './components/sidenav-content/sidenav-content.component';
import { ViewstateListComponent } from './components/viewstate-list/viewstate-list.component';
import { MatDividerModule } from '@angular/material/divider';
import { MatRippleModule } from '@angular/material/core';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ShareViewstateDialogComponent } from './components/share-viewstate-dialog/share-viewstate-dialog.component';
import { MatBadgeModule } from '@angular/material/badge';

@NgModule({
  declarations: [
    LiveCompileComponent,
    SourceEditorComponent,
    DiagnosticListComponent,
    SidenavContentComponent,
    ViewstateListComponent,
    ShareViewstateDialogComponent,
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
    RenamingGraphViewModule,
    MatTooltipModule,
    MatDialogModule,
    MatSlideToggleModule,
    SettingsModule,
    MatSelectModule,
    MatSidenavModule,
    MatDividerModule,
    MatRippleModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatBadgeModule,
  ],
})
export class LiveCompileModule {
}
