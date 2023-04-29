import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API } from '../../auth/api.interceptor';

@Injectable({
  providedIn: 'root',
})
export class ViewstateService {
  constructor(private readonly httpClient: HttpClient) {
  }

  public test(): Observable<string> {
    return this.httpClient.get<string>(`${API}/viewstate`);
  }
}
