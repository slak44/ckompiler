import { Injectable } from '@angular/core';
import { ViewStateService } from '../../settings/services/view-state.service';
import { recentPublicShareLinks } from '@cki-settings';
import { extractMetadataFromState } from '../../settings/models/view-state.model';
import { ActivatedRouteSnapshot, CanActivate, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { catchError, map, Observable, of, tap } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';

const RECENT_SHARED_LINKS_TO_KEEP = 5;

@Injectable({
  providedIn: 'root',
})
export class PublicShareRedirectGuard implements CanActivate {
  constructor(
    private readonly viewStateService: ViewStateService,
    private readonly router: Router,
    private readonly snackBar: MatSnackBar,
  ) {
  }

  private showLongDismissableSnack(message: string): void {
    const ref = this.snackBar.open(message, 'DISMISS', {
      duration: 30_000,
    });
    ref.onAction().subscribe(() => ref.dismiss());
  }

  public canActivate(
    route: ActivatedRouteSnapshot,
    _state: RouterStateSnapshot,
  ): Observable<boolean | UrlTree> | Promise<boolean | UrlTree> | boolean | UrlTree {
    return this.viewStateService.fetchAndRestoreState(route.params['stateId'] as string).pipe(
      tap(viewState => {
        this.showLongDismissableSnack(`Loaded public view state "${viewState.name}"`);
        const existing = recentPublicShareLinks.snapshot.filter(state => state.id !== viewState.id);
        const links = [extractMetadataFromState(viewState), ...existing].slice(0, RECENT_SHARED_LINKS_TO_KEEP);
        recentPublicShareLinks.update(links);
      }),
      catchError(error => {
        console.error(error);
        this.showLongDismissableSnack('Failed to load public view state. ' +
          'It might have been deleted, or its creator might have stopped sharing it.');

        return of(null);
      }),
      map(() => this.router.createUrlTree(['/graph-view'])),
    );
  }
}
