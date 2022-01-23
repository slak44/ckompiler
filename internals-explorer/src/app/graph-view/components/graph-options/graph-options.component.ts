import { ChangeDetectionStrategy, Component } from '@angular/core';
import { slak } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { Observable, shareReplay, startWith } from 'rxjs';
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

  public readonly printingValue$: Observable<string> = (this.printingControl.valueChanges as Observable<string>).pipe(
    startWith(this.printingControl.value as string),
    shareReplay({ refCount: false, bufferSize: 1 }),
  );

  constructor() {
  }

  public getCodePrintingMethodName(value: string): string {
    return getCodePrintingNameJs(value);
  }
}