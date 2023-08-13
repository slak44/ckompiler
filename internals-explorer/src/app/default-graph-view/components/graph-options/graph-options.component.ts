import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CodePrintingMethods } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { map, Observable } from 'rxjs';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { currentPrintingType, hideGraphUI, isSpillOnly } from '@cki-settings';

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

  public readonly printingTypeControl: FormControl = currentPrintingType.formControl;

  public readonly uiHiddenControl: FormControl = hideGraphUI.formControl;

  public readonly isUIVisible$: Observable<boolean> = hideGraphUI.value$.pipe(
    map(isHidden => !isHidden),
  );

  public readonly spillOnlyControl: FormControl = isSpillOnly.formControl;

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
