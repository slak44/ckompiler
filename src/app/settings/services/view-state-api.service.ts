import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API } from '../../auth/api.interceptor';
import { ViewState, ViewStateListing } from '../models/view-state.model';

@Injectable({
  providedIn: 'root',
})
export class ViewStateApiService {
  constructor(private readonly httpClient: HttpClient) {
  }

  public save(viewState: ViewState): Observable<ViewState> {
    return this.httpClient.post<ViewState>(`${API}/viewstate`, viewState);
  }

  public saveAutosave(viewState: ViewState): Observable<ViewState> {
    return this.httpClient.post<ViewState>(`${API}/viewstate/autosave`, viewState);
  }

  public getList(): Observable<ViewStateListing[]> {
    return this.httpClient.get<ViewStateListing[]>(`${API}/viewstate/list`);
  }

  public getById(id: string): Observable<ViewState> {
    return this.httpClient.get<ViewState>(`${API}/viewstate/${id}`);
  }

  public deleteById(id: string): Observable<void> {
    return this.httpClient.delete<void>(`${API}/viewstate/${id}`);
  }

  public configurePublicShare(id: string, isEnabled: boolean): Observable<void> {
    return this.httpClient.patch<void>(`${API}/viewstate/${id}/share-config`, isEnabled);
  }
}
