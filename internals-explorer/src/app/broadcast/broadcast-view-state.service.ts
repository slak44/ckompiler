import { Injectable } from '@angular/core';
import { BroadcastId, BroadcastService } from './broadcast.service';
import { ViewStateService } from '../settings/services/view-state.service';
import {
  animationFrameScheduler,
  EMPTY,
  filter,
  map,
  merge,
  Observable,
  of,
  pairwise, startWith,
  switchMap,
  takeUntil,
  tap,
  throttleTime,
} from 'rxjs';
import { ViewStateNonMetadataDelta } from '../settings/models/view-state.model';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { InitialUserStateService } from '../settings/services/initial-user-state.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BroadcastMessageType, ViewStateMessage } from './models/broadcast-message.model';
import { SnackbarService } from '../material-utils/services/snackbar.service';

@Injectable({
  providedIn: 'root',
})
export class BroadcastViewStateService extends SubscriptionDestroy {
  private readonly publishExpired$: Observable<unknown> = this.broadcastService.broadcastPublishId$.pipe(
    filter(publishId => !publishId),
  );

  private readonly subscribeExpired$: Observable<unknown> = this.broadcastService.broadcastSubscribeId$.pipe(
    filter(subscribeId => !subscribeId),
  );

  constructor(
    private readonly broadcastService: BroadcastService,
    private readonly viewStateService: ViewStateService,
    private readonly initialUserStateService: InitialUserStateService,
    private readonly snackBar: MatSnackBar,
    private readonly snackbarService: SnackbarService,
  ) {
    super();

    this.startBroadcasting(this.initialUserStateService.initialUserState$.pipe(
      switchMap(state => state.activeBroadcast?.id ? of(state.activeBroadcast?.id) : EMPTY),
    ));
  }

  public startBroadcasting(broadcastId$: Observable<BroadcastId | null> = of(null)): void {
    broadcastId$.pipe(
      switchMap(broadcastId => broadcastId ? of(broadcastId) : this.broadcastService.create()),
      tap(broadcastId => this.broadcastService.setBroadcastState({ publishId: broadcastId })),
      switchMap(() => this.viewStateService.viewStateData$.pipe(
        startWith(null),
        pairwise(),
        map(([oldViewState, currentViewState]): ViewStateNonMetadataDelta => {
          if (!currentViewState) {
            throw Error('Unreachable code, viewStateData$ should never emit null');
          }
          if (!oldViewState) {
            return currentViewState;
          }
          if (oldViewState.sourceCode === currentViewState.sourceCode) {
            return { ...currentViewState, sourceCode: null };
          } else {
            return currentViewState;
          }
        }),
        throttleTime(0, animationFrameScheduler),
        takeUntil(this.publishExpired$),
      )),
      takeUntil(this.destroy$),
    ).subscribe(viewState => this.broadcastService.publish(viewState));
  }

  public stopBroadcasting(broadcastId: BroadcastId): void {
    this.broadcastService.close(broadcastId).pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.broadcastService.setBroadcastState({});
      this.snackBar.open('Broadcast closed.', undefined, { duration: 2000 });
    });
  }

  public subscribeToBroadcast(broadcastId: BroadcastId): void {
    this.viewStateService.setAutosaveEnabledState(false);
    this.broadcastService.setBroadcastState({ subscribeId: broadcastId });
    this.broadcastService.watch().pipe(
      tap(message => {
        switch (message.type) {
          case BroadcastMessageType.BROADCAST_CLOSE:
            this.broadcastService.setBroadcastState({});
            this.snackbarService.showLongSnackWithDismiss('The presenter closed this broadcast.');
            break;
          case BroadcastMessageType.SUBSCRIBER_CHANGE:
            // TODO: show subscribers in UI
            console.log(message);
            break;
          case BroadcastMessageType.VIEW_STATE:
            // Handled below as stream
            break;
          default:
            console.error(`Unknown message type - ${JSON.stringify(message)}`);
            break;
        }
      }),
      filter((message): message is ViewStateMessage => message.type === BroadcastMessageType.VIEW_STATE),
      map(message => message.viewState),
      this.viewStateService.restoreStateStream(),
      takeUntil(merge(this.destroy$, this.subscribeExpired$)),
    ).subscribe();
  }
}
