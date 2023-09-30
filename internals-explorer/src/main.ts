import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { PreloadAllModules, provideRouter, withPreloading } from '@angular/router';
import { appRoutes } from './app/app-routes';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { apiInterceptor } from './app/auth/api.interceptor';
import { authHttpInterceptorFn, provideAuth0 } from '@auth0/auth0-angular';
import { environment } from './environments/environment';
import { AUTHENTICATED_ROUTE } from '@cki-utils/routes';
import { importProvidersFrom } from '@angular/core';
import { monacoLoader } from '@cki-utils/monaco-loader';
import { HIGHLIGHT_OPTIONS } from 'ngx-highlightjs';
import { MAT_SNACK_BAR_DEFAULT_OPTIONS, MatSnackBarConfig, MatSnackBarModule } from '@angular/material/snack-bar';
import { MonacoEditorModule } from 'ng-monaco-editor';

bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(appRoutes, withPreloading(PreloadAllModules)),
    provideAnimations(),
    provideHttpClient(withInterceptors([
      apiInterceptor,
      authHttpInterceptorFn,
    ])),
    provideAuth0({
      domain: environment.oauth.domain,
      clientId: environment.oauth.clientId,
      authorizationParams: {
        audience: environment.oauth.audience,
        scope: 'openid profile email offline_access',
        redirect_uri: `${environment.rootUrl}/${AUTHENTICATED_ROUTE}`,
      },
      useRefreshTokens: true,
      cacheLocation: 'localstorage',
      httpInterceptor: {
        allowedList: [
          {
            // For public share links
            httpMethod: 'GET',
            uri: `${environment.apiBaseUrl}/viewstate/*`,
            allowAnonymous: true,
          },
          {
            uri: `${environment.apiBaseUrl}/*`,
          },
        ],
      },
    }),
    importProvidersFrom(MonacoEditorModule.forRoot({
      dynamicImport: () => import('monaco-editor').then(monacoLoader),
    })),
    {
      provide: HIGHLIGHT_OPTIONS,
      useValue: {
        coreLibraryLoader: () => import('highlight.js/lib/core'),
        languages: {
          c: () => import('highlight.js/lib/languages/c'),
          x86asm: () => import('highlight.js/lib/languages/x86asm'),
          mipsasm: () => import('highlight.js/lib/languages/mipsasm'),
        },
      },
    },
    importProvidersFrom(MatSnackBarModule),
    {
      provide: MAT_SNACK_BAR_DEFAULT_OPTIONS,
      useValue: {
        verticalPosition: 'top',
        duration: 10_000,
      } as MatSnackBarConfig,
    },
  ],
}).catch(console.error);
