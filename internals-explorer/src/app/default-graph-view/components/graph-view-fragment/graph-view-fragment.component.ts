import { ChangeDetectionStrategy, Component, HostBinding, Input, ValueProvider } from '@angular/core';
import { FRAGMENT_COMPONENT, FragmentComponent } from '@cki-graph-view/models/fragment-component.model';
import { slak } from '@ckompiler/ckompiler';
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

@Component({
  selector: 'cki-graph-view-fragment',
  templateUrl: './graph-view-fragment.component.html',
  styleUrls: ['./graph-view-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphViewFragmentComponent implements FragmentComponent {
  public static provider: ValueProvider = {
    provide: FRAGMENT_COMPONENT,
    useValue: GraphViewFragmentComponent
  };

  @Input()
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public text: string = '';

  @Input()
  public printingType: CodePrintingMethods = CodePrintingMethods.IR_TO_STRING;

  public readonly CodePrintingMethods = CodePrintingMethods;

  constructor() {
  }
}
