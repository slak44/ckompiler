import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { CommonModule } from '@angular/common';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';

@Component({
  selector: 'cki-select-function',
  templateUrl: './select-function.component.html',
  styleUrls: ['./select-function.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatFormFieldModule,
    MatSelectModule,
  ],
})
export class SelectFunctionComponent {
  @Input()
  public instance!: CompilationInstance;
}
