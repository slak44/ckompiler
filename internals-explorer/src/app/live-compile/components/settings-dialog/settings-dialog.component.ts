import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatSlideToggleChange } from '@angular/material/slide-toggle';

enum Settings {
  TRANSPARENCY = 'transparency'
}

function generateKey(setting: Settings): string {
  return `ckompiler-setting-${setting}`;
}

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
