import { ChangeDetectionStrategy, Component, Input, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Observable, takeUntil } from 'rxjs';
import { CompileService } from '../../services/compile.service';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { editor, MarkerSeverity } from 'monaco-editor';
import IMarkerData = editor.IMarkerData;
import { slak } from '@ckompiler/ckompiler';
import closedRangeLength = slak.ckompiler.closedRangeLength;
import diagnosticKindString = slak.ckompiler.diagnosticKindString;

@Component({
  selector: 'cki-source-editor',
  templateUrl: './source-editor.component.html',
  styleUrls: ['./source-editor.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourceEditorComponent extends SubscriptionDestroy implements OnInit {
  @Input()
  public initialText$?: Observable<string>;

  public readonly monacoOptions = {
    theme: 'darcula',
    language: 'c',
  };

  public readonly sourceControl: FormControl = new FormControl('');

  constructor(
    private compileService: CompileService,
  ) {
    super();
  }

  public ngOnInit(): void {
    this.sourceControl.valueChanges.pipe(
      takeUntil(this.destroy$)
    ).subscribe((text: string) => {
      this.compileService.changeSourceText(text);
    });

    if (this.initialText$) {
      this.initialText$.subscribe(text => this.sourceControl.setValue(text));
    }

    this.compileService.allDiagnostics$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(diagnostics => {
      const markers = diagnostics.map((diagnostic): IMarkerData => {
        const data = diagnostic.dataFor(diagnostic.caret);
        const length = closedRangeLength(diagnostic.caret);
        const kind = diagnosticKindString(diagnostic);

        const types: Record<string, MarkerSeverity> = {
          ERROR: MarkerSeverity.Error,
          WARNING: MarkerSeverity.Warning,
          OTHER: MarkerSeverity.Info
        };

        return {
          message: diagnostic.formattedMessage,
          source: 'ckompiler',
          startLineNumber: data.line,
          endLineNumber: data.line,
          startColumn: data.column + 1,
          endColumn: data.column + length + 1,
          severity: types[kind] ?? MarkerSeverity.Info,
        };
      });

      // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
      window.monaco!.editor.setModelMarkers(window.monaco!.editor.getModels()[0], null!, markers);
    });
  }
}
