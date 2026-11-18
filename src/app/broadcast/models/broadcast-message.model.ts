import { MousePosition, ViewStateNonMetadataDelta } from '../../settings/models/view-state.model';

export enum BroadcastMessageType {
  VIEW_STATE = 'VIEW_STATE',
  BROADCAST_METADATA = 'BROADCAST_METADATA',
  SUBSCRIBER_CHANGE = 'SUBSCRIBER_CHANGE',
  BROADCAST_CLOSE = 'BROADCAST_CLOSE',
}

interface BroadcastBaseMessage {
  type: BroadcastMessageType
}

export interface ViewStateMessage extends BroadcastBaseMessage {
  type: BroadcastMessageType.VIEW_STATE;
  viewState: ViewStateNonMetadataDelta | null;
  pos: MousePosition;
}

export interface BroadcastMetadataMessage extends BroadcastBaseMessage {
  type: BroadcastMessageType.BROADCAST_METADATA;
  broadcasterName: string;
}

export interface SubscriberChangeMessage extends BroadcastBaseMessage {
  type: BroadcastMessageType.SUBSCRIBER_CHANGE;
  subscribers: string[];
}

export interface BroadcastCloseMessage extends BroadcastBaseMessage {
  type: BroadcastMessageType.BROADCAST_CLOSE;
}

export type BroadcastMessage = ViewStateMessage | BroadcastMetadataMessage | SubscriberChangeMessage | BroadcastCloseMessage;
