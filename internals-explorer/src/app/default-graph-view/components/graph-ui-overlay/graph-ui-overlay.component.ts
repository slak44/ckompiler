import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { Observable } from 'rxjs';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { hideGraphUI } from '@cki-settings';

@Component({
  selector: 'cki-graph-ui-overlay',
  templateUrl: './graph-ui-overlay.component.html',
  styleUrls: ['./graph-ui-overlay.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphUiOverlayComponent {
  @Input()
  public instance!: CompilationInstance;

  public readonly isUIHidden$: Observable<boolean> = hideGraphUI.value$;

  constructor() {
  }
}
