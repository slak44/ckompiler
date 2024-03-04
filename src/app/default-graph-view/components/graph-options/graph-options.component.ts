import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CodePrintingMethods } from '@ckompiler/ckompiler';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { map, Observable } from 'rxjs';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { currentPrintingType, hideGraphUI, isSpillOnly } from '@cki-settings';
import { CommonModule } from '@angular/common';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { SelectFunctionComponent } from '@cki-graph-view/components/select-function/select-function.component';

@Component({
  selector: 'cki-graph-options',
  templateUrl: './graph-options.component.html',
  styleUrls: ['./graph-options.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatCheckboxModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatSelectModule,
    SelectFunctionComponent,
  ],
})
export class GraphOptionsComponent {
  @Input()
  public instance!: CompilationInstance;

  public readonly codePrintingMethods: CodePrintingMethods[] = CodePrintingMethods.values();

  public readonly printingTypeControl: FormControl<CodePrintingMethods> = currentPrintingType.formControl;

  public readonly uiHiddenControl: FormControl<boolean> = hideGraphUI.formControl;

  public readonly isUIVisible$: Observable<boolean> = hideGraphUI.value$.pipe(
    map(isHidden => !isHidden),
  );

  public readonly spillOnlyControl: FormControl<boolean> = isSpillOnly.formControl;

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
