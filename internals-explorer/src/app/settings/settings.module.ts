import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SettingsDialogComponent } from './components/settings-dialog/settings-dialog.component';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ReactiveFormsModule } from '@angular/forms';

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
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
})
export class SettingsModule {
}
