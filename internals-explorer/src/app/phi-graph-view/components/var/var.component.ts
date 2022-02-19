import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'cki-var',
  templateUrl: './var.component.html',
  styleUrls: ['./var.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VarComponent {
  constructor() {
  }
}
