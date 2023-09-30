import { ChangeDetectionStrategy, Component } from '@angular/core';
import { defaultFunctionName, hasTransparency } from '@cki-settings';
import { FormControl } from '@angular/forms';

@Component({
  selector: 'cki-settings-dialog',
  templateUrl: './settings-dialog.component.html',
  styleUrls: ['./settings-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsDialogComponent {
  public readonly transparencyControl: FormControl<boolean> = hasTransparency.formControl;
  public readonly defaultFunctionNameControl: FormControl<string> = defaultFunctionName.formControl;

  constructor() {
  }
}
