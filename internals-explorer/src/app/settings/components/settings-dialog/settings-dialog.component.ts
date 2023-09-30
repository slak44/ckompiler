import { ChangeDetectionStrategy, Component } from '@angular/core';
import { defaultFunctionName, hasTransparency } from '@cki-settings';
import { UntypedFormControl } from '@angular/forms';

@Component({
  selector: 'cki-settings-dialog',
  templateUrl: './settings-dialog.component.html',
  styleUrls: ['./settings-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsDialogComponent {
  public readonly transparencyControl: UntypedFormControl = hasTransparency.formControl;
  public readonly defaultFunctionNameControl: UntypedFormControl = defaultFunctionName.formControl;

  constructor() {
  }
}
