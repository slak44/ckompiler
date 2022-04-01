import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SettingsDialogComponent } from './components/settings-dialog/settings-dialog.component';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatDialogModule } from '@angular/material/dialog';

@NgModule({
  declarations: [
    SettingsDialogComponent,
  ],
  exports: [
    SettingsDialogComponent,
  ],
  imports: [
    CommonModule,
    MatSlideToggleModule,
    MatDialogModule,
  ],
})
export class SettingsModule {
}
