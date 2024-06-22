import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API } from '../../auth/api.interceptor';
import { UserState } from '../models/user-state.model';

@Injectable({
  providedIn: 'root',
})
export class UserStateService {
  constructor(private readonly httpClient: HttpClient) {
  }

  public getCurrentState(): Observable<UserState> {
    return this.httpClient.get<UserState>(`${API}/userstate`);
  }
}
