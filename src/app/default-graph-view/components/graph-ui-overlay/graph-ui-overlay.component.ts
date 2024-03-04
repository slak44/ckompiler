import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { Observable } from 'rxjs';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { hideGraphUI } from '@cki-settings';
import { CommonModule } from '@angular/common';
import { GraphOptionsComponent } from '../graph-options/graph-options.component';
import { GraphLegendComponent } from '../graph-legend/graph-legend.component';

@Component({
  selector: 'cki-graph-ui-overlay',
  templateUrl: './graph-ui-overlay.component.html',
  styleUrls: ['./graph-ui-overlay.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    GraphOptionsComponent,
    GraphLegendComponent,
  ],
})
export class GraphUiOverlayComponent {
  @Input()
  public instance!: CompilationInstance;

  public readonly isUIHidden$: Observable<boolean> = hideGraphUI.value$;
}
