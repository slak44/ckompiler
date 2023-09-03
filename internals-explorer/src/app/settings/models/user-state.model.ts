import { ViewState } from './view-state.model';
import { ActiveBroadcast } from '../../broadcast/models/active-broadcast.model';

export interface UserState {
  id: string;
  autosaveViewState: ViewState | null;
  activeBroadcast: ActiveBroadcast | null;
}
