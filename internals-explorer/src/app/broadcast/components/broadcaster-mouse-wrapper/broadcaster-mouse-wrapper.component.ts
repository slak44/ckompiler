import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BroadcasterMouseComponent } from '../broadcaster-mouse/broadcaster-mouse.component';
import { BroadcastId, BroadcastService } from '../../services/broadcast.service';
import { BroadcastViewStateService } from '../../services/broadcast-view-state.service';
import { GraphMouseTrackerService } from '../../services/graph-mouse-tracker.service';
import { map, Observable } from 'rxjs';
import { MousePosition } from '../../../settings/models/view-state.model';

@Component({
  selector: 'cki-broadcaster-mouse-wrapper',
  standalone: true,
  imports: [CommonModule, BroadcasterMouseComponent],
  templateUrl: './broadcaster-mouse-wrapper.component.html',
  styleUrls: ['./broadcaster-mouse-wrapper.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BroadcasterMouseWrapperComponent {
  public readonly broadcastSubscribeId$: Observable<BroadcastId | undefined> =
    this.broadcastService.broadcastSubscribeId$;

  public readonly cssBroadcasterName$: Observable<string> = this.broadcastViewStateService.broadcasterName$.pipe(
    map(name => `"${name ?? ''}"`),
  );

  public readonly broadcasterMouse$: Observable<MousePosition> = this.graphMouseTrackerService.broadcasterMouse$.pipe(
    map((point) => this.graphMouseTrackerService.svgSpaceToScreenPoint(point)),
  );

  constructor(
    private readonly broadcastService: BroadcastService,
    private readonly broadcastViewStateService: BroadcastViewStateService,
    private readonly graphMouseTrackerService: GraphMouseTrackerService,
  ) {
  }

  protected readonly name = name;
}
