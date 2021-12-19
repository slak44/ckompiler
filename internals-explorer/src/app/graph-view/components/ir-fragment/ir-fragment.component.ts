import { ChangeDetectionStrategy, Component, HostBinding, Input } from '@angular/core';

export const irFragmentComponentSelector = 'cki-ir-fragment';

@Component({
  selector: irFragmentComponentSelector,
  templateUrl: './ir-fragment.component.html',
  styleUrls: ['./ir-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IrFragmentComponent {
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public text: string = '';

  @Input()
  public printingType: string = 'IR_TO_STRING';

  constructor() {
  }
}
