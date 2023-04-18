import { ChangeDetectionStrategy, Component } from '@angular/core';
import { AuthService } from '@auth0/auth0-angular';

@Component({
  selector: 'cki-sidenav-content',
  templateUrl: './sidenav-content.component.html',
  styleUrls: ['./sidenav-content.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SidenavContentComponent {
  public readonly user$ = this.authService.user$;

  constructor(private readonly authService: AuthService) {
  }

  public login(): void {
    this.authService.loginWithRedirect();
  }

  public logout(): void {
    this.authService.logout();
  }
}
