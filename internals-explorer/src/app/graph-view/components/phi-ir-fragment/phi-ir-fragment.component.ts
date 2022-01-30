import { ChangeDetectionStrategy, Component, HostBinding, Input, ValueProvider } from '@angular/core';
import { FRAGMENT_COMPONENT, FragmentComponent } from '../../models/fragment-component.model';

@Component({
  selector: 'cki-phi-ir-fragment',
  templateUrl: './phi-ir-fragment.component.html',
  styleUrls: ['./phi-ir-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PhiIrFragmentComponent implements FragmentComponent {
  public static provider: ValueProvider = {
    provide: FRAGMENT_COMPONENT,
    useValue: PhiIrFragmentComponent
  };

  @Input()
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public text: string = '';

  constructor() {
  }
}
