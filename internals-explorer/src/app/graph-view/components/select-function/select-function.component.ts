import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';

@Component({
  selector: 'cki-select-function',
  templateUrl: './select-function.component.html',
  styleUrls: ['./select-function.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelectFunctionComponent {
  @Input()
  public instance!: CompilationInstance;

  constructor() {
  }
}
