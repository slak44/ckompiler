import { EnvironmentType } from './environment-type';

export const environment: EnvironmentType = {
  production: true,
  baseUrl: 'http://128.140.49.235/api',
  oauth: {
    domain: 'ckompiler-internals-explorer.eu.auth0.com',
    clientId: 'VX6YGntpyUipXIFXHff4clmJe2geq8rb',
    audience: 'https://slak44.github.io/ckompiler/',
    redirectBaseUri: 'https://slak44.github.io/ckompiler',
  },
};
