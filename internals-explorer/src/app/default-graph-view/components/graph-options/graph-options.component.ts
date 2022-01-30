import { ChangeDetectionStrategy, Component } from '@angular/core';
import { slak } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { map, Observable, shareReplay } from 'rxjs';
import { controlValueStream } from '@cki-utils/form-control-observable';
import codePrintingMethods = slak.ckompiler.codePrintingMethods;
import getCodePrintingNameJs = slak.ckompiler.getCodePrintingNameJs;

@Component({
  selector: 'cki-graph-options',
  templateUrl: './graph-options.component.html',
  styleUrls: ['./graph-options.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphOptionsComponent {
  public readonly codePrintingMethods: string[] = codePrintingMethods;

  public readonly printingControl: FormControl = new FormControl('IR_TO_STRING');

  public readonly printingValue$: Observable<string> = controlValueStream<string>(this.printingControl).pipe(
    shareReplay({ refCount: false, bufferSize: 1 }),
  );

  public readonly uiHiddenControl: FormControl = new FormControl(false);

  public readonly isUIVisible$: Observable<boolean> = controlValueStream<boolean>(this.uiHiddenControl).pipe(
    map(isHidden => !isHidden),
    shareReplay({ refCount: false, bufferSize: 1 }),
  );

  constructor() {
  }

  public getCodePrintingMethodName(value: string): string {
    return getCodePrintingNameJs(value);
  }
}
