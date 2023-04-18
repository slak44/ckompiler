import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HIGHLIGHT_OPTIONS } from 'ngx-highlightjs';
import { HTTP_INTERCEPTORS, HttpClientModule } from '@angular/common/http';
import { MonacoEditorModule } from 'ng-monaco-editor';
import { monacoLoader } from '@cki-utils/monaco-loader';
import { AuthHttpInterceptor, AuthModule } from '@auth0/auth0-angular';
import { environment } from '../environments/environment';
import { AUTHENTICATED_ROUTE } from '@cki-utils/routes';

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
      dynamicImport: () => import('monaco-editor').then(monacoLoader),
    }),
    AuthModule.forRoot({
      domain: environment.oauth.domain,
      clientId: environment.oauth.clientId,
      authorizationParams: {
        audience: environment.oauth.audience,
        scope: 'openid profile email offline_access',
        redirect_uri: `${window.location.origin}/${AUTHENTICATED_ROUTE}`,
      },
      useRefreshTokens: true,
      cacheLocation: 'localstorage',
      httpInterceptor: {
        allowedList: [
          {
            uri: `${environment.baseUrl}/*`,
          },
        ],
      },
    }),
  ],
  providers: [
    { provide: HTTP_INTERCEPTORS, useClass: AuthHttpInterceptor, multi: true },
    {
      provide: HIGHLIGHT_OPTIONS,
      useValue: {
        coreLibraryLoader: () => import('highlight.js/lib/core'),
        languages: {
          c: () => import('highlight.js/lib/languages/c'),
          x86asm: () => import('highlight.js/lib/languages/x86asm'),
        },
      },
    },
  ],
  bootstrap: [AppComponent],
})
export class AppModule {
}
