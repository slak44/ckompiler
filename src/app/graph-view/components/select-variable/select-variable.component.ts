import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { Observable } from 'rxjs';
import { Variable } from '@ckompiler/ckompiler';
import { Setting } from '@cki-settings';
import { CommonModule } from '@angular/common';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { ReactiveFormsModule } from '@angular/forms';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'cki-select-variable',
  templateUrl: './select-variable.component.html',
  styleUrls: ['./select-variable.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatFormFieldModule,
    MatSelectModule,
    ReactiveFormsModule,
    MatTooltipModule,
    MatButtonModule,
    MatIconModule,
  ],
})
export class SelectVariableComponent {
  @Input()
  public variableIdSetting!: Setting<number | null>;

  @Input()
  public variables$!: Observable<Variable[]>;

  @Output()
  public readonly startClick: EventEmitter<void> = new EventEmitter<void>();
}
