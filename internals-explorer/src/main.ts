import { enableProdMode } from '@angular/core';
import { platformBrowserDynamic } from '@angular/platform-browser-dynamic';

import { AppModule } from './app/app.module';
import { environment } from './environments/environment';

if (environment.production) {
  enableProdMode();
}

window.__hpcc_wasmFolder = 'assets/graphviz-wasm';

platformBrowserDynamic().bootstrapModule(AppModule)
  .catch(err => console.error(err));
