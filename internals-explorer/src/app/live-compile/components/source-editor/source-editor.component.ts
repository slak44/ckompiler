import { ChangeDetectionStrategy, Component, Input, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { first, Observable, takeUntil } from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import type * as Monaco from 'monaco-editor';
import { editor, MarkerSeverity } from 'monaco-editor';
import { slak } from '@ckompiler/ckompiler';
import { monacoLoaded$ } from '@cki-utils/monaco-loader';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { monacoFontSize, monacoViewState } from '@cki-settings';
import IMarkerData = editor.IMarkerData;
import closedRangeLength = slak.ckompiler.closedRangeLength;
import diagnosticKindString = slak.ckompiler.diagnosticKindString;
import EditorOption = editor.EditorOption;

const STORAGE_KEY_SOURCE_CODE = 'source-code';

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
  };

  public readonly sourceControl: FormControl = new FormControl('');

  private lastUnloadListener: (() => void) | undefined = undefined;

  constructor(
    private readonly compileService: CompileService,
  ) {
    super();
  }

  public ngOnInit(): void {
    this.sourceControl.valueChanges.pipe(
      takeUntil(this.destroy$),
    ).subscribe((text: string) => {
      localStorage.setItem(STORAGE_KEY_SOURCE_CODE, text);
      this.compileService.changeSourceText(text);
    });

    const lastText = localStorage.getItem(STORAGE_KEY_SOURCE_CODE);
    if (lastText) {
      this.sourceControl.setValue(lastText);
    } else if (this.initialText$) {
      this.initialText$.subscribe(text => this.sourceControl.setValue(text));
    }

    monacoLoaded$.pipe(first()).subscribe((monaco) => this.setMarkersFromDiagnostics(monaco));
  }

  private setMarkersFromDiagnostics(monaco: typeof Monaco): void {
    this.compileService.allDiagnostics$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(diagnostics => {
      const markers = diagnostics.map((diagnostic): IMarkerData => {
        const data = diagnostic.dataFor(diagnostic.caret);
        const length = closedRangeLength(diagnostic.caret);
        const kind = diagnosticKindString(diagnostic);

        const types: Record<string, MarkerSeverity> = {
          ERROR: MarkerSeverity.Error,
          WARNING: MarkerSeverity.Warning,
          OTHER: MarkerSeverity.Info,
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
