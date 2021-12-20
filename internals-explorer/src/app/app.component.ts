import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Location } from '@angular/common';

@Component({
  selector: 'cki-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  constructor(
    private location: Location,
  ) {
    window.__hpcc_wasmFolder = this.location.prepareExternalUrl('/assets/graphviz-wasm');
  }
}
