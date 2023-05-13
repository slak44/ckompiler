export interface EnvironmentType {
  production: boolean;
  baseUrl: string;
  oauth: {
    domain: string;
    clientId: string;
    audience: string;
    redirectBaseUri: string;
  };
}
