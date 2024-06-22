import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { AuthService } from '@auth0/auth0-angular';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { takeUntil } from 'rxjs';
import { environment } from '../environments/environment';

@Component({
  selector: 'cki-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
  ],
})
export class AppComponent extends SubscriptionDestroy {
  constructor(
    private readonly authService: AuthService,
    private readonly location: Location,
  ) {
    super();

    window.__hpcc_wasmFolder = this.location.prepareExternalUrl('/assets/graphviz-wasm');
    const workerPath = this.location.prepareExternalUrl('/assets/graphviz-wasm/index.min.js');
    document.head.insertAdjacentHTML('beforeend', `<script src="${workerPath}" type="javascript/worker"></script>`);

    this.authService.error$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(error => {
      console.error(error);

      if (error.message.includes('Unknown or invalid refresh token')) {
        this.authService.logout({
          logoutParams: {
            returnTo: environment.rootUrl,
          },
        });
      }
    });
  }
}
