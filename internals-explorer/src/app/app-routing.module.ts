import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { AuthCallbackGuard } from './auth/auth-callback.guard';
import { AUTHENTICATED_ROUTE, PUBLIC_SHARE_ROUTE } from '@cki-utils/routes';
import { PublicShareRedirectGuard } from './live-compile/guards/public-share-redirect.guard';

const routes: Routes = [
  {
    path: AUTHENTICATED_ROUTE,
    canActivate: [AuthCallbackGuard],
    children: [],
  },
  {
    path: `${PUBLIC_SHARE_ROUTE}/:stateId`,
    canActivate: [PublicShareRedirectGuard],
    children: [],
  },
  {
    path: 'graph-view',
    loadChildren: () => import('./live-compile/live-compile.module').then(m => m.LiveCompileModule)
  },
  {
    path: "**",
    redirectTo: "/graph-view"
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
