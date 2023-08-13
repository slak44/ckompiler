import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { Observable } from 'rxjs';
import { Variable } from '@ckompiler/ckompiler';
import { Setting } from '@cki-settings';

@Component({
  selector: 'cki-select-variable',
  templateUrl: './select-variable.component.html',
  styleUrls: ['./select-variable.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelectVariableComponent {
  @Input()
  public variableIdSetting!: Setting<number | null>;

  @Input()
  public variables$!: Observable<Variable[]>;

  @Output()
  public readonly startClick: EventEmitter<void> = new EventEmitter<void>();
}
