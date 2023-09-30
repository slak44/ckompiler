import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HIGHLIGHT_OPTIONS } from 'ngx-highlightjs';
import { HTTP_INTERCEPTORS, HttpClientModule } from '@angular/common/http';
import { MonacoEditorModule } from 'ngx-monaco-editor-v2';
import { monacoLoader } from '@cki-utils/monaco-loader';
import { AuthHttpInterceptor, AuthModule } from '@auth0/auth0-angular';
import { environment } from '../environments/environment';
import { AUTHENTICATED_ROUTE } from '@cki-utils/routes';
import { ApiInterceptor } from './auth/api.interceptor';
import { MAT_LEGACY_SNACK_BAR_DEFAULT_OPTIONS as MAT_SNACK_BAR_DEFAULT_OPTIONS, MatLegacySnackBarConfig as MatSnackBarConfig, MatLegacySnackBarModule as MatSnackBarModule } from '@angular/material/legacy-snack-bar';

@NgModule({
  declarations: [
    AppComponent,
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule,
    HttpClientModule,
    MonacoEditorModule.forRoot({
      onMonacoLoad: monacoLoader,
    }),
    AuthModule.forRoot({
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
    MatSnackBarModule,
  ],
  providers: [
    { provide: HTTP_INTERCEPTORS, useClass: ApiInterceptor, multi: true },
    { provide: HTTP_INTERCEPTORS, useClass: AuthHttpInterceptor, multi: true },
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
    {
      provide: MAT_SNACK_BAR_DEFAULT_OPTIONS,
      useValue: {
        verticalPosition: 'top',
        duration: 10_000,
      } as MatSnackBarConfig,
    },
  ],
  bootstrap: [AppComponent],
})
export class AppModule {
}
