import { ViewStateNonMetadataDelta } from '../../settings/models/view-state.model';

export enum BroadcastMessageType {
  VIEW_STATE = 'VIEW_STATE',
  SUBSCRIBER_CHANGE = 'SUBSCRIBER_CHANGE',
  BROADCAST_CLOSE = 'BROADCAST_CLOSE',
}

interface BroadcastBaseMessage {
  type: BroadcastMessageType
}

export interface ViewStateMessage extends BroadcastBaseMessage {
  type: BroadcastMessageType.VIEW_STATE;
  viewState: ViewStateNonMetadataDelta;
}

export interface SubscriberChangeMessage extends BroadcastBaseMessage {
  type: BroadcastMessageType.SUBSCRIBER_CHANGE;
  subscribers: string[];
}

export interface BroadcastCloseMessage extends BroadcastBaseMessage {
  type: BroadcastMessageType.BROADCAST_CLOSE;
}

export type BroadcastMessage = ViewStateMessage | SubscriberChangeMessage | BroadcastCloseMessage;
