import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveCompileComponent } from './components/live-compile/live-compile.component';
import { DiagnosticListComponent } from './components/diagnostic-list/diagnostic-list.component';
import { SourceEditorComponent } from './components/source-editor/source-editor.component';
import { LiveCompileRoutingModule } from './live-compile-routing.module';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MonacoEditorModule } from 'ngx-monaco-editor-v2';
import { MatLegacyTabsModule as MatTabsModule } from '@angular/material/legacy-tabs';
import { TabRoutingModule } from '../tab-routing/tab-routing.module';
import { MatLegacyChipsModule as MatChipsModule } from '@angular/material/legacy-chips';
import { DefaultGraphViewModule } from '../default-graph-view/default-graph-view.module';
import { PhiGraphViewModule } from '../phi-graph-view/phi-graph-view.module';
import { ReactiveFormsModule } from '@angular/forms';
import { MatLegacyButtonModule as MatButtonModule } from '@angular/material/legacy-button';
import { RenamingGraphViewModule } from '../renaming-graph-view/renaming-graph-view.module';
import { MatLegacyTooltipModule as MatTooltipModule } from '@angular/material/legacy-tooltip';
import { MatLegacyDialogModule as MatDialogModule } from '@angular/material/legacy-dialog';
import { MatLegacySlideToggleModule as MatSlideToggleModule } from '@angular/material/legacy-slide-toggle';
import { SettingsModule } from '../settings/settings.module';
import { MatLegacySelectModule as MatSelectModule } from '@angular/material/legacy-select';
import { MatSidenavModule } from '@angular/material/sidenav';
import { SidenavContentComponent } from './components/sidenav-content/sidenav-content.component';
import { ViewstateListComponent } from './components/viewstate-list/viewstate-list.component';
import { MatDividerModule } from '@angular/material/divider';
import { MatRippleModule } from '@angular/material/core';
import { MatLegacyInputModule as MatInputModule } from '@angular/material/legacy-input';
import { MatLegacyProgressSpinnerModule as MatProgressSpinnerModule } from '@angular/material/legacy-progress-spinner';
import { ShareViewstateDialogComponent } from './components/share-viewstate-dialog/share-viewstate-dialog.component';
import { MatBadgeModule } from '@angular/material/badge';
import { BroadcastModule } from '../broadcast/broadcast.module';

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
    BroadcastModule,
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
