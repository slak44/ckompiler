import { ChangeDetectionStrategy, Component } from '@angular/core';
import { BroadcastService } from '../../services/broadcast.service';
import { map, Observable } from 'rxjs';
import { BROADCAST_ROUTE } from '@cki-utils/routes';
import { BroadcastViewStateService } from '../../services/broadcast-view-state.service';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'cki-active-broadcast-banner',
  templateUrl: './active-broadcast-banner.component.html',
  styleUrls: ['./active-broadcast-banner.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule
  ]
})
export class ActiveBroadcastBannerComponent {
  public readonly publishUrl$: Observable<string | undefined> = this.broadcastService.broadcastPublishId$.pipe(
    map(broadcastId => {
      return !broadcastId ? undefined : `${window.location.origin}/${BROADCAST_ROUTE}/${broadcastId}`;
    }),
  );

  public readonly subscribeUrl$: Observable<string | undefined> = this.broadcastService.broadcastSubscribeId$.pipe(
    map(broadcastId => {
      return !broadcastId ? undefined : `${window.location.origin}/${BROADCAST_ROUTE}/${broadcastId}`;
    }),
  );

  public readonly subscribers$: Observable<string[]> = this.broadcastViewStateService.subscribers$;

  constructor(
    private readonly broadcastService: BroadcastService,
    private readonly broadcastViewStateService: BroadcastViewStateService,
  ) {
  }
}
