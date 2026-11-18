import { inject } from '@angular/core';
import { Router, UrlTree } from '@angular/router';
import { AuthService } from '@auth0/auth0-angular';

export function authCallbackGuard(): UrlTree {
  inject(AuthService).handleRedirectCallback();

  return inject(Router).createUrlTree(['/']);
}
