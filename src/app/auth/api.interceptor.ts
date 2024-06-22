import { HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export const API = '@api';

export const apiInterceptor = (request: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
  if (request.url.startsWith(API)) {
    let newHeaders = request.headers;
    if (!newHeaders.has('Content-Type')) {
      newHeaders = newHeaders.set('Content-Type', 'application/json');
    }

    const newRequest = request.clone({
      url: request.url.replace(API, environment.apiBaseUrl),
      headers: newHeaders,
    });

    return next(newRequest);
  }
  return next(request);
};
