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
      { path: SOURCE_CODE_PATH, children: [] },
      { path: DIAGNOSTICS_PATH, children: [] },
      { path: CFG_PATH, children: [] },
      { path: PHI_PATH, children: [] },
      { path: RENAME_PATH, children: [] },
    ],
  },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class LiveCompileRoutingModule {
}
