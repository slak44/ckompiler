import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

const routes: Routes = [
  {
    path: 'graph-view',
    loadChildren: () => import('./live-compile/live-compile.module').then(m => m.LiveCompileModule)
  },
  {
    path: "**",
    redirectTo: "/graph-view"
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
