import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatSlideToggleChange } from '@angular/material/slide-toggle';
import { generateKey, Settings } from '@cki-settings';

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
    localStorage.setItem(generateKey(Settings.TRANSPARENCY), `${change.checked}`);
  }
}
