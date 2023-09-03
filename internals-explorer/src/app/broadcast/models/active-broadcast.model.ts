import { BroadcastId } from '../broadcast.service';

export interface ActiveBroadcast {
  id: BroadcastId;
  presenterUserId: string;
  subscribers: string[];
}
