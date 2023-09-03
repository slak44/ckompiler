import { ChangeDetectionStrategy, Component, OnInit } from '@angular/core';
import { BroadcastService } from '../../broadcast.service';
import { map, Observable } from 'rxjs';
import { BROADCAST_ROUTE } from '@cki-utils/routes';

@Component({
  selector: 'cki-active-broadcast-banner',
  templateUrl: './active-broadcast-banner.component.html',
  styleUrls: ['./active-broadcast-banner.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActiveBroadcastBannerComponent implements OnInit {
  public readonly publishUrl$: Observable<string | undefined> = this.broadcastService.broadcastPublishId$.pipe(
    map(broadcastId => {
      return !broadcastId ? undefined : `${window.location.origin}/${BROADCAST_ROUTE}/${broadcastId}`;
    })
  );

  constructor(private readonly broadcastService: BroadcastService) {
  }

  public ngOnInit(): void {
  }
}
