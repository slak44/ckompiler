export interface EnvironmentType {
  production: boolean;
  broadcastUrl: string;
  apiBaseUrl: string;
  rootUrl: string;
  serviceWorker: {
    scope?: string;
    enabled: boolean;
  };
  oauth: {
    domain: string;
    clientId: string;
    audience: string;
  };
}
