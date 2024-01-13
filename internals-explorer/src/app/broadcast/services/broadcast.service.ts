import { Injectable, OnDestroy } from '@angular/core';
import { RxStomp, RxStompConfig } from '@stomp/rx-stomp';
import { BehaviorSubject, EMPTY, first, map, merge, Observable, skip, switchMap, takeUntil, tap } from 'rxjs';
import { ViewStateNonMetadataDelta } from '../../settings/models/view-state.model';
import { environment } from '../../../environments/environment';
import { AuthService } from '@auth0/auth0-angular';
import { HttpClient } from '@angular/common/http';
import { API } from '../../auth/api.interceptor';
import { Brand } from '@cki-utils/brand';
import { BroadcastMessage } from '../models/broadcast-message.model';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';

export type BroadcastId = Brand<string, 'BroadcastId'>;

interface NoBroadcastState {
  publishId?: BroadcastId;
  subscribeId?: BroadcastId;
}

interface PublishBroadcastState {
  publishId: BroadcastId;
  subscribeId?: undefined;
}

interface SubscribedBroadcastState {
  publishId?: undefined;
  subscribeId: BroadcastId;
}

export type BroadcastState = PublishBroadcastState | SubscribedBroadcastState | NoBroadcastState;

@Injectable({
  providedIn: 'root',
})
export class BroadcastService extends SubscriptionDestroy implements OnDestroy {
  private readonly rxStomp: RxStomp = new RxStomp();

  private readonly broadcastStateSubject: BehaviorSubject<BroadcastState> = new BehaviorSubject<BroadcastState>({});

  public readonly broadcastPublishId$: Observable<BroadcastId | undefined> = this.broadcastStateSubject.pipe(
    skip(1),
    map(state => state.publishId)
  );

  public readonly broadcastSubscribeId$: Observable<BroadcastId | undefined> = this.broadcastStateSubject.pipe(
    map(state => state.subscribeId)
  );

  constructor(
    private readonly authService: AuthService,
    private readonly httpClient: HttpClient,
  ) {
    super();

    // FIXME: this breaks if websocket disconnects then reconnects after token was refreshed
    // FIXME: unauthenticated users just get stuck
    this.authService.isAuthenticated$.pipe(
      first(isAuthenticated => isAuthenticated),
      switchMap(() => this.authService.getAccessTokenSilently({ cacheMode: 'cache-only' })),
      first(),
      tap((token) => this.rxStomp.configure(this.generateWebsocketConfig(token))),
      switchMap(() => this.broadcastStateSubject),
    ).subscribe((state) => {
      if (state.subscribeId) {
        this.rxStomp.activate();
      } else if (state.publishId) {
        this.rxStomp.activate();
      } else {
        this.rxStomp.deactivate().catch(console.error);
      }
    });

    this.rxStomp.stompErrors$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(error => {
      this.broadcastStateSubject.next({});
      console.error('[STOMP ERROR]', error.headers, error.body);
    });
  }

  public override ngOnDestroy(): void {
    super.ngOnDestroy();
    this.broadcastStateSubject.complete();
    this.rxStomp.deactivate().catch(console.error);
  }

  public setBroadcastState(broadcastState: BroadcastState): void {
    this.broadcastStateSubject.next(broadcastState);
  }

  public create(): Observable<BroadcastId> {
    return this.httpClient.post(`${API}/broadcast/create`, {}, { responseType: 'text' }) as Observable<BroadcastId>;
  }

  public close(broadcastId: BroadcastId): Observable<void> {
    return this.httpClient.post<void>(`${API}/broadcast/${broadcastId}/close`, {});
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

  public watchPublished(): Observable<BroadcastMessage> {
    const publishId = this.broadcastStateSubject.value.publishId;
    if (!publishId) {
      console.error('Cannot publish without a publishId');

      return EMPTY;
    }

    return this.subscribeToBroadcastTopic(publishId);
  }

  public watch(): Observable<BroadcastMessage> {
    const subscribeId = this.broadcastStateSubject.value.subscribeId;
    if (!subscribeId) {
      console.error('Cannot subscribe without a subscribeId');
      return EMPTY;
    }

    return this.subscribeToBroadcastTopic(subscribeId);
  }

  private subscribeToBroadcastTopic(broadcastId: BroadcastId): Observable<BroadcastMessage> {
    return merge(
      this.rxStomp.watch({
        destination: `/user/subscribe/broadcast/${broadcastId}`,
      }),
      this.rxStomp.watch({
        destination: `/subscribe/broadcast/${broadcastId}`,
      }),
    ).pipe(
      map(message => JSON.parse(message.body) as BroadcastMessage),
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
