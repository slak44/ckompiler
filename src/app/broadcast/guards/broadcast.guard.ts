import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { BroadcastViewStateService } from '../services/broadcast-view-state.service';
import { BroadcastId } from '../services/broadcast.service';
import { GRAPH_VIEW_ROUTE } from '@cki-utils/routes';
import { AuthService } from '@auth0/auth0-angular';
import { firstValueFrom } from 'rxjs';

export const BROADCAST_ID_PARAM = 'broadcastId';

export async function broadcastGuard(
  route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
): Promise<boolean | UrlTree> {
  const broadcastViewStateService = inject(BroadcastViewStateService);
  const router = inject(Router);
  const authService = inject(AuthService);

  const isAuthenticated = await firstValueFrom(authService.isAuthenticated$);

  if (!isAuthenticated) {
    authService.loginWithRedirect({
      appState: {
        target: state.url,
      },
    });

    return false;
  }

  broadcastViewStateService.subscribeToBroadcast(route.params[BROADCAST_ID_PARAM] as BroadcastId);

  return router.createUrlTree([`/${GRAPH_VIEW_ROUTE}`]);
}
