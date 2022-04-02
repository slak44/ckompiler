import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatSlideToggleChange } from '@angular/material/slide-toggle';
import { defaultFunctionName, hasTransparency } from '@cki-settings';

@Component({
  selector: 'cki-settings-dialog',
  templateUrl: './settings-dialog.component.html',
  styleUrls: ['./settings-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsDialogComponent {
  public readonly initialTransparency: boolean = hasTransparency.snapshot;

  public readonly initialDefaultFunctionName = defaultFunctionName.snapshot;

  constructor() {
  }

  public onTransparencyChange(change: MatSlideToggleChange): void {
    hasTransparency.update(change.checked);
  }

  public onDefaultFunctionNameChange(value: string): void {
    defaultFunctionName.update(value);
  }
}
