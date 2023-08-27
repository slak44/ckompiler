import { Injectable } from '@angular/core';
import { BroadcastService } from './broadcast.service';
import { ViewStateService } from '../settings/services/view-state.service';
import { animationFrameScheduler, map, pairwise, takeUntil, throttleTime } from 'rxjs';
import { ViewStateNonMetadataDelta } from '../settings/models/view-state.model';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';

@Injectable({
  providedIn: 'root',
})
export class BroadcastViewStateService extends SubscriptionDestroy {
  constructor(
    private readonly broadcastService: BroadcastService,
    private readonly viewStateService: ViewStateService,
  ) {
    super();
  }

  public startBroadcast(): void {
    this.broadcastService.setBroadcastState({ publishId: 'test' });
    this.viewStateService.viewStateData$.pipe(
      pairwise(),
      map(([oldViewState, currentViewState]): ViewStateNonMetadataDelta => {
        if (oldViewState.sourceCode === currentViewState.sourceCode) {
          return { ...currentViewState, sourceCode: null };
        } else {
          return currentViewState;
        }
      }),
      throttleTime(16.6, animationFrameScheduler),
      takeUntil(this.destroy$),
    ).subscribe(viewState => this.broadcastService.publish(viewState));
  }

  public subscribeToBroadcast(): void {
    this.viewStateService.setAutosaveEnabledState(false);
    this.broadcastService.setBroadcastState({ subscribeId: 'test' });
    this.viewStateService.restoreStateStream(this.broadcastService.watch()).pipe(
      takeUntil(this.destroy$),
    ).subscribe();
  }
}
