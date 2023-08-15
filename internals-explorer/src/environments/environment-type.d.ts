export interface EnvironmentType {
  production: boolean;
  apiBaseUrl: string;
  rootUrl: string;
  oauth: {
    domain: string;
    clientId: string;
    audience: string;
  };
}
