import { inject } from '@angular/core';
import { ViewStateService } from '../../settings/services/view-state.service';
import { recentPublicShareLinks } from '@cki-settings';
import { extractMetadataFromState } from '../../settings/models/view-state.model';
import { ActivatedRouteSnapshot, Router, UrlTree } from '@angular/router';
import { catchError, map, Observable, of } from 'rxjs';
import { GRAPH_VIEW_ROUTE } from '@cki-utils/routes';
import { SnackbarService } from '../../material-utils/services/snackbar.service';

const RECENT_SHARED_LINKS_TO_KEEP = 5;

export const STATE_ID_PARAM = 'stateId';

export function publicShareRedirectGuard(route: ActivatedRouteSnapshot): Observable<UrlTree> {
  const snackbarService = inject(SnackbarService);
  const router = inject(Router);

  return inject(ViewStateService).fetchAndRestoreState(route.params[STATE_ID_PARAM] as string, true).pipe(
    map(viewState => {
      snackbarService.showLongSnackWithDismiss(`Loaded public view state "${viewState.name}"`);
      const existing = recentPublicShareLinks.snapshot.filter(state => state.id !== viewState.id);
      const links = [extractMetadataFromState(viewState), ...existing].slice(0, RECENT_SHARED_LINKS_TO_KEEP);
      recentPublicShareLinks.update(links);

      return viewState.activeRoute;
    }),
    catchError(error => {
      console.error(error);
      snackbarService.showLongSnackWithDismiss('Failed to load public view state. ' +
        'It might have been deleted, or its creator might have stopped sharing it.');

      return of(`/${GRAPH_VIEW_ROUTE}`);
    }),
    map(activeRoute => router.createUrlTree([activeRoute])),
  );
}
