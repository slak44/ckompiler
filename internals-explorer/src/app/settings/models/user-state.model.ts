import { ViewState } from './view-state.model';

export interface UserState {
  id: string;
  autosaveViewState: ViewState | null;
}
