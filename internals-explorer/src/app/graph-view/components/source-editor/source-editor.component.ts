import { ChangeDetectionStrategy, Component, Input, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Observable } from 'rxjs';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';

@Component({
  selector: 'cki-source-editor',
  templateUrl: './source-editor.component.html',
  styleUrls: ['./source-editor.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourceEditorComponent implements OnInit {
  @Input()
  public initialText$?: Observable<string>;

  public readonly monacoOptions = {
    theme: 'vs-dark',
    language: 'c'
  };

  public readonly sourceControl: FormControl = new FormControl('');

  public readonly debouncedSource$: Observable<string> = this.sourceControl.valueChanges.pipe(
    debounceAfterFirst(500),
  );

  constructor() {
  }

  public ngOnInit(): void {
    if (this.initialText$) {
      this.initialText$.subscribe(text => this.sourceControl.setValue(text));
    }
  }
}
