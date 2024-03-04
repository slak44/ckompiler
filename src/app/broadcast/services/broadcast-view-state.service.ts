import { Injectable } from '@angular/core';
import { BroadcastId, BroadcastService } from './broadcast.service';
import { ViewStateService } from '../../settings/services/view-state.service';
import {
  animationFrameScheduler,
  combineLatest,
  EMPTY,
  filter,
  finalize,
  map,
  merge,
  Observable,
  of,
  pairwise,
  pipe,
  ReplaySubject,
  share,
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
import { GraphMouseTrackerService } from './graph-mouse-tracker.service';

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

  private readonly broadcasterNameSubject: ReplaySubject<string> = new ReplaySubject<string>(1);

  public readonly broadcasterName$: Observable<string> = this.broadcasterNameSubject;

  private readonly subscribersSubject: ReplaySubject<string[]> = new ReplaySubject<string[]>(1);

  public readonly subscribers$: Observable<string[]> = this.subscribersSubject;

  constructor(
    private readonly broadcastService: BroadcastService,
    private readonly viewStateService: ViewStateService,
    private readonly initialUserStateService: InitialUserStateService,
    private readonly snackBar: MatSnackBar,
    private readonly snackbarService: SnackbarService,
    private readonly graphMouseTrackerService: GraphMouseTrackerService,
  ) {
    super();

    this.startBroadcasting(this.initialUserStateService.initialUserState$.pipe(
      switchMap(state => state.activeBroadcast?.id ? of(state.activeBroadcast?.id) : EMPTY),
    ));
  }

  private buildDeltaViewState(
    oldViewState: ViewStateNonMetadataDelta | undefined,
    currentViewState: ViewStateNonMetadataDelta
  ): ViewStateNonMetadataDelta | null {
    if (!currentViewState) {
      throw Error('Unreachable code, viewStateData$ should never emit null');
    } else if (!oldViewState) {
      // No previous state - this only ever happens on the first emission, due to a startWith with an empty array
      return currentViewState;
    } else if (oldViewState === currentViewState) {
      // View state unchanged (same object reference) => only send changed mouse position, no view state
      return null;
    } else if (oldViewState.sourceCode === currentViewState.sourceCode) {
      // View state changed, but the source code didn't
      return { ...currentViewState, sourceCode: null };
    } else {
      // Source code changed, resend full object
      return currentViewState;
    }
  }

  public startBroadcasting(broadcastId$: Observable<BroadcastId | null> = of(null)): void {
    const broadcastCreate$ = broadcastId$.pipe(
      switchMap(broadcastId => broadcastId ? of(broadcastId) : this.broadcastService.create()),
      tap(broadcastId => this.broadcastService.setBroadcastState({ publishId: broadcastId })),
      share(),
    );

    const publishPipe = pipe(
      switchMap(() => combineLatest([
        this.viewStateService.viewStateData$,
        this.graphMouseTrackerService.mousePosition$,
      ]).pipe(
        // Keep throttle before map - otherwise, mouse moves that never get sent would set null state every time
        throttleTime(0, animationFrameScheduler),
        startWith([]),
        pairwise(),
        map(([[oldViewState,], [currentViewState, pos]]) => {
          const viewState = this.buildDeltaViewState(oldViewState, currentViewState);
          return { state: viewState, pos };
        }),
        takeUntil(this.publishExpired$),
      )),
      tap(viewStateMessage => this.broadcastService.publish(viewStateMessage)),
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
          case BroadcastMessageType.BROADCAST_METADATA:
            this.broadcasterNameSubject.next(message.broadcasterName);
            break;
          case BroadcastMessageType.VIEW_STATE:
            this.graphMouseTrackerService.setBroadcasterMousePosition(message.pos);
            // State itself is handled below as stream
            break;
          default:
            console.error(`Unknown message type - ${JSON.stringify(message)}`);
            break;
        }
      }),
      filter((message): message is ViewStateMessage => message.type === BroadcastMessageType.VIEW_STATE),
      map(message => message.viewState),
      filter((viewState): viewState is ViewStateNonMetadataDelta => viewState !== null),
      this.viewStateService.restoreStateStream(),
      finalize(() => this.viewStateService.setAutosaveEnabledState(true)),
      takeUntil(merge(this.destroy$, this.subscribeExpired$)),
    ).subscribe();
  }
}
