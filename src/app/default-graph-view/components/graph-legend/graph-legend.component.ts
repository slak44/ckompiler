import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'cki-graph-legend',
  templateUrl: './graph-legend.component.html',
  styleUrls: ['./graph-legend.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
  ],
})
export class GraphLegendComponent {
}
