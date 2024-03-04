import { ChangeDetectionStrategy, Component } from '@angular/core';
import { AuthService } from '@auth0/auth0-angular';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { ViewstateListComponent } from '../viewstate-list/viewstate-list.component';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'cki-sidenav-content',
  templateUrl: './sidenav-content.component.html',
  styleUrls: ['./sidenav-content.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatDividerModule,
    ViewstateListComponent,
  ],
})
export class SidenavContentComponent {
  public readonly user$ = this.authService.user$;

  constructor(private readonly authService: AuthService) {
  }

  public login(): void {
    this.authService.loginWithRedirect();
  }

  public logout(): void {
    this.authService.logout({
      logoutParams: {
        returnTo: environment.rootUrl,
      },
    });
  }
}
