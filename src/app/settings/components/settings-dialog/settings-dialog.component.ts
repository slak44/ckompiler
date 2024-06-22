import { ChangeDetectionStrategy, Component } from '@angular/core';
import { defaultFunctionName, hasTransparency } from '@cki-settings';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'cki-settings-dialog',
  templateUrl: './settings-dialog.component.html',
  styleUrls: ['./settings-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatSlideToggleModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
})
export class SettingsDialogComponent {
  public readonly transparencyControl: FormControl<boolean> = hasTransparency.formControl;
  public readonly defaultFunctionNameControl: FormControl<string> = defaultFunctionName.formControl;

  constructor() {
  }
}
