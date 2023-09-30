import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, Router, UrlTree } from '@angular/router';
import { BroadcastViewStateService } from '../services/broadcast-view-state.service';
import { BroadcastId } from '../services/broadcast.service';
import { GRAPH_VIEW_ROUTE } from '@cki-utils/routes';

export const BROADCAST_ID_PARAM = 'broadcastId';

export function broadcastGuard(route: ActivatedRouteSnapshot): UrlTree {
  inject(BroadcastViewStateService).subscribeToBroadcast(route.params[BROADCAST_ID_PARAM] as BroadcastId);

  return inject(Router).createUrlTree([`/${GRAPH_VIEW_ROUTE}`]);

}
