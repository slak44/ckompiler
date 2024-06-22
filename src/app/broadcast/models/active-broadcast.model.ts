import { BroadcastId } from '../services/broadcast.service';

export interface ActiveBroadcast {
  id: BroadcastId;
  presenterUserId: string;
  subscribers: string[];
}
