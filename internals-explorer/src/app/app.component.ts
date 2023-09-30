import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'cki-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
  ],
})
export class AppComponent {
  constructor(
    private readonly location: Location,
  ) {
    window.__hpcc_wasmFolder = this.location.prepareExternalUrl('/assets/graphviz-wasm');
    const workerPath = this.location.prepareExternalUrl('/assets/graphviz-wasm/index.min.js');
    document.head.insertAdjacentHTML('beforeend', `<script src="${workerPath}" type="javascript/worker"></script>`);
  }
}
