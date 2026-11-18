import { NEVER, Observable, of, switchMap } from 'rxjs';
import { AuthService } from '@auth0/auth0-angular';

export function subscribeIfAuthenticated(authService: AuthService): Observable<void> {
  return authService.user$.pipe(
    switchMap(currentUser => {
      if (currentUser) {
        return of(void null);
      } else {
        return NEVER;
      }
    }),
  );
}
