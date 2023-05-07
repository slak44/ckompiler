import { ChangeDetectionStrategy, Component, Input, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { first, Observable, takeUntil } from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import type * as Monaco from 'monaco-editor';
import { editor, MarkerSeverity } from 'monaco-editor';
import { slak } from '@ckompiler/ckompiler';
import { monacoLoaded$ } from '@cki-utils/monaco-loader';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { monacoFontSize, monacoViewState, sourceCode } from '@cki-settings';
import IMarkerData = editor.IMarkerData;
import closedRangeLength = slak.ckompiler.closedRangeLength;
import EditorOption = editor.EditorOption;
import DiagnosticKind = slak.ckompiler.DiagnosticKind;

@Component({
  selector: 'cki-source-editor',
  templateUrl: './source-editor.component.html',
  styleUrls: ['./source-editor.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourceEditorComponent extends SubscriptionDestroy implements OnInit {
  @Input()
  public initialText$?: Observable<string>;

  public readonly monacoOptions: editor.IStandaloneEditorConstructionOptions = {
    theme: 'darcula',
    language: 'c',
    fontFamily: 'Fira Code',
    fontSize: 14, // This is the default font size, reset font size comes back to this value
    useShadowDOM: true,
    renderWhitespace: 'trailing',
    tabSize: 2,
  };

  public readonly sourceControl: FormControl = sourceCode.formControl;

  private lastUnloadListener: (() => void) | undefined = undefined;

  constructor(
    private readonly compileService: CompileService,
  ) {
    super();
  }

  public ngOnInit(): void {
    if (sourceCode.snapshot === '' && this.initialText$) {
      this.initialText$.pipe(takeUntil(this.destroy$)).subscribe(text => sourceCode.update(text));
    }

    monacoLoaded$.pipe(first()).subscribe((monaco) => {
      this.setMarkersFromDiagnostics(monaco);

      document.fonts.load(`${this.monacoOptions.fontSize}px ${this.monacoOptions.fontFamily}`).then(() => {
        // Fix renderWhitespace with custom font
        monaco.editor.remeasureFonts();
      }).catch((err) => {
        console.error('Font loading error: ', err);
      });
    });
  }

  private setMarkersFromDiagnostics(monaco: typeof Monaco): void {
    this.compileService.allDiagnostics$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(diagnostics => {
      const markers = diagnostics.map((diagnostic): IMarkerData => {
        const data = diagnostic.dataFor(diagnostic.caret);
        const length = closedRangeLength(diagnostic.caret);
        const kind = diagnostic.id.kind.name;

        const types: Record<string, MarkerSeverity> = {
          [DiagnosticKind.ERROR.name]: MarkerSeverity.Error,
          [DiagnosticKind.WARNING.name]: MarkerSeverity.Warning,
          [DiagnosticKind.OTHER.name]: MarkerSeverity.Info,
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

      monaco.editor.setModelMarkers(monaco.editor.getModels()[0], null!, markers);
    });
  }

  public onEditorChange(editor: editor.IStandaloneCodeEditor): void {
    if (this.lastUnloadListener) {
      window.removeEventListener('unload', this.lastUnloadListener);
    }

    const existingState = monacoViewState.snapshot;
    if (existingState) {
      editor.restoreViewState(existingState);
    }

    editor.updateOptions({ fontSize: monacoFontSize.snapshot });

    this.lastUnloadListener = () => {
      monacoViewState.update(editor.saveViewState());
      monacoFontSize.update(editor.getOption(EditorOption.fontSize));
    };

    window.addEventListener('unload', this.lastUnloadListener);
  }
}
