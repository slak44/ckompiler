import { ChangeDetectionStrategy, Component, HostBinding, Input } from '@angular/core';

export const graphViewFragmentSelector = 'cki-graph-view-fragment';

@Component({
  selector: graphViewFragmentSelector,
  templateUrl: './graph-view-fragment.component.html',
  styleUrls: ['./graph-view-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphViewFragmentComponent {
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public text: string = '';

  @Input()
  public printingType: string = 'IR_TO_STRING';

  constructor() {
  }
}
