import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { Observable } from 'rxjs';
import { BroadcastViewStateService } from '../services/broadcast-view-state.service';
import { BroadcastId } from '../services/broadcast.service';
import { GRAPH_VIEW_ROUTE } from '@cki-utils/routes';

export const BROADCAST_ID_PARAM = 'broadcastId';

@Injectable({
  providedIn: 'root',
})
export class BroadcastGuard  {
  constructor(
    private readonly broadcastViewStateService: BroadcastViewStateService,
    private readonly router: Router,
  ) {
  }

  public canActivate(
    route: ActivatedRouteSnapshot,
    _state: RouterStateSnapshot,
  ): Observable<boolean | UrlTree> | Promise<boolean | UrlTree> | boolean | UrlTree {
    this.broadcastViewStateService.subscribeToBroadcast(route.params[BROADCAST_ID_PARAM] as BroadcastId);

    return this.router.createUrlTree([`/${GRAPH_VIEW_ROUTE}`]);
  }
}
