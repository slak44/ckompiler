import { Injectable } from '@angular/core';
import { AuthService } from '@auth0/auth0-angular';
import { Observable, shareReplay, switchMap } from 'rxjs';
import { subscribeIfAuthenticated } from '@cki-utils/subscribe-if-authenticated';
import { UserStateService } from './user-state.service';
import { UserState } from '../models/user-state.model';

@Injectable({
  providedIn: 'root',
})
export class InitialUserStateService {
  public readonly initialUserState$: Observable<UserState> = subscribeIfAuthenticated(this.authService).pipe(
    switchMap(() => this.userStateService.getCurrentState()),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  constructor(
    private readonly authService: AuthService,
    private readonly userStateService: UserStateService,
  ) {
  }
}
