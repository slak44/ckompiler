import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'cki-graph-view',
  templateUrl: './graph-view.component.html',
  styleUrls: ['./graph-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GraphViewComponent {
  constructor() {
  }
}
