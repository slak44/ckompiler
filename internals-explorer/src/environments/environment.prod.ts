import { EnvironmentType } from './environment-type';

export const environment: EnvironmentType = {
  production: true,
  apiBaseUrl: 'https://ckompiler.slak44.dev/api',
  rootUrl: 'https://slak44.github.io/ckompiler',
  oauth: {
    domain: 'ckompiler-internals-explorer.eu.auth0.com',
    clientId: 'VX6YGntpyUipXIFXHff4clmJe2geq8rb',
    audience: 'https://slak44.github.io/ckompiler/',
  },
};
