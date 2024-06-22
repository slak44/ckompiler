import { Routes } from '@angular/router';
import { authCallbackGuard } from './auth/auth-callback.guard';
import {
  AUTHENTICATED_ROUTE,
  BROADCAST_ROUTE,
  CFG_PATH,
  DIAGNOSTICS_PATH,
  GRAPH_VIEW_ROUTE,
  PHI_PATH,
  PUBLIC_SHARE_ROUTE,
  RENAME_PATH,
  SOURCE_CODE_PATH,
} from '@cki-utils/routes';
import { publicShareRedirectGuard, STATE_ID_PARAM } from './live-compile/guards/public-share-redirect.guard';
import { BROADCAST_ID_PARAM, broadcastGuard } from './broadcast/guards/broadcast.guard';

export const appRoutes: Routes = [
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
    loadComponent: () => import('./live-compile/components/live-compile/live-compile.component')
      .then(m => m.LiveCompileComponent),
    children: [
      { path: SOURCE_CODE_PATH, children: [] },
      { path: DIAGNOSTICS_PATH, children: [] },
      { path: CFG_PATH, children: [] },
      { path: PHI_PATH, children: [] },
      { path: RENAME_PATH, children: [] },
    ],
  },
  {
    path: '**',
    redirectTo: `/${GRAPH_VIEW_ROUTE}`,
  },
];
