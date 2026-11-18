import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'cki-var',
  templateUrl: './var.component.html',
  styleUrls: ['./var.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule
  ]
})
export class VarComponent {
}
