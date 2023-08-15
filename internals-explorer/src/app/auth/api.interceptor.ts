import { Injectable } from '@angular/core';
import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor
} from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export const API = '@api';

@Injectable()
export class ApiInterceptor implements HttpInterceptor {

  constructor() {}

  public intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    if (request.url.startsWith(API)) {
      let newHeaders = request.headers;
      if (!newHeaders.has('Content-Type')) {
        newHeaders = newHeaders.set('Content-Type', 'application/json');
      }

      const newRequest = request.clone({
        url: request.url.replace(API, environment.apiBaseUrl),
        headers: newHeaders,
      });

      return next.handle(newRequest);
    }
    return next.handle(request);
  }
}
