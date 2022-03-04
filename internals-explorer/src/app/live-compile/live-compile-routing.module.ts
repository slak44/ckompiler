import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import {
  CFG_PATH,
  DIAGNOSTICS_PATH,
  LiveCompileComponent,
  PHI_PATH,
  RENAME_PATH,
  SOURCE_CODE_PATH,
} from './components/live-compile/live-compile.component';

const routes: Routes = [
  {
    path: '',
    component: LiveCompileComponent,
    children: [
      { path: SOURCE_CODE_PATH },
      { path: DIAGNOSTICS_PATH },
      { path: CFG_PATH },
      { path: PHI_PATH },
      { path: RENAME_PATH },
    ],
  },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class LiveCompileRoutingModule {
}
