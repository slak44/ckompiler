import { Injectable, OnDestroy } from '@angular/core';
import { RxStomp, RxStompConfig } from '@stomp/rx-stomp';
import { BehaviorSubject, EMPTY, first, map, Observable, switchMap, tap } from 'rxjs';
import { ViewStateNonMetadataDelta } from '../settings/models/view-state.model';
import { environment } from '../../environments/environment';
import { AuthService } from '@auth0/auth0-angular';

interface NoBroadcastState {
  publishId?: undefined;
  subscribeId?: undefined;
}

interface PublishBroadcastState {
  publishId: string;
  subscribeId?: undefined;
}

interface SubscribedBroadcastState {
  publishId?: undefined;
  subscribeId: string;
}

export type BroadcastState = PublishBroadcastState | SubscribedBroadcastState | NoBroadcastState;


@Injectable({
  providedIn: 'root',
})
export class BroadcastService implements OnDestroy {
  private readonly rxStomp: RxStomp = new RxStomp();

  private readonly broadcastStateSubject: BehaviorSubject<BroadcastState> = new BehaviorSubject<BroadcastState>({});
  public readonly broadcastState$: Observable<BroadcastState> = this.broadcastStateSubject;

  constructor(private readonly authService: AuthService) {
    this.authService.isAuthenticated$.pipe(
      first(isAuthenticated => isAuthenticated),
      switchMap(() => this.authService.getAccessTokenSilently({ cacheMode: 'cache-only' })),
      first(),
      tap((token) => this.rxStomp.configure(this.generateWebsocketConfig(token))),
      switchMap(() => this.broadcastState$),
    ).subscribe((state) => {
      if (state.subscribeId) {
        this.rxStomp.activate();
      } else if (state.publishId) {
        this.rxStomp.activate();
      } else {
        this.rxStomp.deactivate().catch(console.error);
      }
    });
  }

  public ngOnDestroy(): void {
    this.broadcastStateSubject.complete();
    this.rxStomp.deactivate().catch(console.error);
  }

  public setBroadcastState(broadcastState: BroadcastState): void {
    this.broadcastStateSubject.next(broadcastState);
  }

  public publish(viewState: ViewStateNonMetadataDelta): void {
    const publishId = this.broadcastStateSubject.value.publishId;
    if (!publishId) {
      console.error('Cannot publish without a publishId');
      return;
    }

    this.rxStomp.publish({
      destination: `/publish/broadcast/${publishId}`,
      body: JSON.stringify(viewState),
    });
  }

  public watch(): Observable<ViewStateNonMetadataDelta> {
    const subscribeId = this.broadcastStateSubject.value.subscribeId;
    if (!subscribeId) {
      console.error('Cannot subscribe without a subscribeId');
      return EMPTY;
    }

    return this.rxStomp.watch({
      destination: `/subscribe/broadcast/${subscribeId}`,
    }).pipe(
      map(message => JSON.parse(message.body) as ViewStateNonMetadataDelta),
    );
  }

  private generateWebsocketConfig(authToken: string): RxStompConfig {
    const config: RxStompConfig = {
      brokerURL: environment.broadcastUrl,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 20_000,
      reconnectDelay: 1000,
      connectHeaders: {
        passcode: authToken,
      },
    };

    if (!environment.production) {
      config.debug = (msg: string) => console.log(`[STOMP] ${new Date().toISOString()}: ${msg}`);
    }

    return config;
  }
}
