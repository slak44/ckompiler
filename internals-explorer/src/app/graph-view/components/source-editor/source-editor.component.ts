import { ChangeDetectionStrategy, Component, Input, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { Observable, takeUntil } from 'rxjs';
import { CompileService } from '../../services/compile.service';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';

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
  }
}
