import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'cki-graph-legend',
  templateUrl: './graph-legend.component.html',
  styleUrls: ['./graph-legend.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphLegendComponent {
  constructor() {
  }
}
