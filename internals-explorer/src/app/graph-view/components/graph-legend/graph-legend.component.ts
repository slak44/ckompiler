import { Component, OnInit, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'cki-graph-legend',
  templateUrl: './graph-legend.component.html',
  styleUrls: ['./graph-legend.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GraphLegendComponent implements OnInit {

  constructor() { }

  ngOnInit(): void {
  }

}
