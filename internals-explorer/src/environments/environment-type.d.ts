export interface EnvironmentType {
  production: boolean;
  broadcastUrl: string;
  apiBaseUrl: string;
  rootUrl: string;
  oauth: {
    domain: string;
    clientId: string;
    audience: string;
  };
}
