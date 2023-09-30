import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { authCallbackGuard } from './auth/auth-callback.guard';
import { AUTHENTICATED_ROUTE, BROADCAST_ROUTE, GRAPH_VIEW_ROUTE, PUBLIC_SHARE_ROUTE } from '@cki-utils/routes';
import { publicShareRedirectGuard, STATE_ID_PARAM } from './live-compile/guards/public-share-redirect.guard';
import { BROADCAST_ID_PARAM, broadcastGuard } from './broadcast/guards/broadcast.guard';

const routes: Routes = [
  {
    path: AUTHENTICATED_ROUTE,
    canActivate: [authCallbackGuard],
    children: [],
  },
  {
    path: `${PUBLIC_SHARE_ROUTE}/:${STATE_ID_PARAM}`,
    canActivate: [publicShareRedirectGuard],
    children: [],
  },
  {
    path: `${BROADCAST_ROUTE}/:${BROADCAST_ID_PARAM}`,
    canActivate: [broadcastGuard],
    children: [],
  },
  {
    path: GRAPH_VIEW_ROUTE,
    loadChildren: () => import('./live-compile/live-compile.module').then(m => m.LiveCompileModule),
  },
  {
    path: '**',
    redirectTo: `/${GRAPH_VIEW_ROUTE}`,
  },
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { enableTracing: false })],
  exports: [RouterModule],
})
export class AppRoutingModule {
}
