import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatSlideToggleChange } from '@angular/material/slide-toggle';
import { hasTransparencyDisabled } from '@cki-settings';

@Component({
  selector: 'cki-settings-dialog',
  templateUrl: './settings-dialog.component.html',
  styleUrls: ['./settings-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsDialogComponent {
  constructor() {
  }

  public onTransparencyChange(change: MatSlideToggleChange): void {
    hasTransparencyDisabled.update(change.checked);
  }
}
