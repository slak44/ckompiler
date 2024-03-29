import { ChangeDetectionStrategy, Component, HostBinding, Input, ValueProvider } from '@angular/core';
import { FRAGMENT_COMPONENT, FragmentComponent } from '@cki-graph-view/models/fragment-component.model';
import { CodePrintingMethods, ISAType } from '@ckompiler/ckompiler';
import { map, Observable } from 'rxjs';
import { isaType } from '@cki-settings';
import { CommonModule } from '@angular/common';
import { HighlightModule } from 'ngx-highlightjs';

@Component({
  selector: 'cki-graph-view-fragment',
  templateUrl: './graph-view-fragment.component.html',
  styleUrls: ['./graph-view-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    HighlightModule,
  ],
})
export class GraphViewFragmentComponent implements FragmentComponent {
  public static provider: ValueProvider = {
    provide: FRAGMENT_COMPONENT,
    useValue: GraphViewFragmentComponent,
  };

  @Input()
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public text: string = '';

  @Input()
  public printingType: CodePrintingMethods = CodePrintingMethods.IR_TO_STRING;

  public readonly CodePrintingMethods = CodePrintingMethods;

  public readonly language$: Observable<string> = isaType.value$.pipe(
    map(isaType => {
      switch (isaType) {
        case ISAType.X64:
          return 'x86asm';
        case ISAType.MIPS32:
          return 'mipsasm';
        default:
          throw new Error('Missing enum value');
      }
    }),
  );

  constructor() {
  }
}
