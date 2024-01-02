import { Injectable } from '@angular/core';
import { BroadcastId, BroadcastService } from './broadcast.service';
import { ViewStateService } from '../../settings/services/view-state.service';
import {
  animationFrameScheduler,
  EMPTY,
  filter,
  finalize,
  map,
  merge,
  Observable,
  of,
  pairwise, pipe,
  ReplaySubject, share,
  startWith,
  switchMap,
  takeUntil,
  tap,
  throttleTime,
} from 'rxjs';
import { ViewStateNonMetadataDelta } from '../../settings/models/view-state.model';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { InitialUserStateService } from '../../settings/services/initial-user-state.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BroadcastMessageType, ViewStateMessage } from '../models/broadcast-message.model';
import { SnackbarService } from '../../material-utils/services/snackbar.service';

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

  private readonly subscribersSubject: ReplaySubject<string[]> = new ReplaySubject<string[]>(1);

  public readonly subscribers$: Observable<string[]> = this.subscribersSubject;

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
    const broadcastCreate$ = broadcastId$.pipe(
      switchMap(broadcastId => broadcastId ? of(broadcastId) : this.broadcastService.create()),
      tap(broadcastId => this.broadcastService.setBroadcastState({ publishId: broadcastId })),
      share(),
    );

    const publishPipe = pipe(
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
      tap(viewState => this.broadcastService.publish(viewState)),
    );

    const subscriberDataPipe = pipe(
      switchMap(() => this.broadcastService.watchPublished().pipe(
        takeUntil(this.publishExpired$),
      )),
      tap(message => {
        if (message.type === BroadcastMessageType.SUBSCRIBER_CHANGE) {
          this.subscribersSubject.next(message.subscribers);
        }
      }),
    );

    broadcastCreate$.pipe(
      publishPipe,
      takeUntil(this.destroy$),
    ).subscribe();

    broadcastCreate$.pipe(
      subscriberDataPipe,
      takeUntil(this.destroy$),
    ).subscribe();
  }

  public stopBroadcasting(broadcastId: BroadcastId): void {
    this.broadcastService.close(broadcastId).pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.broadcastService.setBroadcastState({});
      this.snackBar.open('Broadcast closed.', undefined, { duration: 2000 });
    });
  }

  public stopWatching(): void {
    this.broadcastService.setBroadcastState({});
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
            this.subscribersSubject.next(message.subscribers);
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
      finalize(() => this.viewStateService.setAutosaveEnabledState(true)),
      takeUntil(merge(this.destroy$, this.subscribeExpired$)),
    ).subscribe();
  }
}
