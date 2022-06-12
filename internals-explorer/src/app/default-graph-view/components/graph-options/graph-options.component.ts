import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { slak } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { map, Observable, shareReplay } from 'rxjs';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

@Component({
  selector: 'cki-graph-options',
  templateUrl: './graph-options.component.html',
  styleUrls: ['./graph-options.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphOptionsComponent {
  @Input()
  public instance!: CompilationInstance;

  public readonly codePrintingMethods: CodePrintingMethods[] = CodePrintingMethods.values();

  public readonly printingControl: FormControl = new FormControl(CodePrintingMethods.IR_TO_STRING);

  public readonly printingValue$: Observable<CodePrintingMethods> =
    controlValueStream<CodePrintingMethods>(this.printingControl).pipe(
      shareReplay({ refCount: false, bufferSize: 1 }),
    );

  public readonly uiHiddenControl: FormControl = new FormControl(false);

  public readonly isUIVisible$: Observable<boolean> = controlValueStream<boolean>(this.uiHiddenControl).pipe(
    map(isHidden => !isHidden),
    shareReplay({ refCount: false, bufferSize: 1 }),
  );

  public readonly spillOnlyControl: FormControl = new FormControl(false);

  public readonly isSpillOnly$: Observable<boolean> = controlValueStream<boolean>(this.spillOnlyControl).pipe(
    shareReplay({ refCount: false, bufferSize: 1 }),
  );

  constructor() {
  }

  public getCodePrintingMethodName(value: CodePrintingMethods): string {
    switch (value.name) {
      case CodePrintingMethods.SOURCE_SUBSTRING.name:
        return 'Source substrings';
      case CodePrintingMethods.EXPR_TO_STRING.name:
        return 'Expressions';
      case CodePrintingMethods.IR_TO_STRING.name:
        return 'IR';
      case CodePrintingMethods.MI_TO_STRING.name:
        return 'Machine instructions';
      case CodePrintingMethods.ASM_TO_STRING.name:
        return 'Assembly';
    }
  }
}
