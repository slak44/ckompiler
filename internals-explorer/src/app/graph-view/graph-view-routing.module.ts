import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { LiveCompileComponent } from './components/live-compile/live-compile.component';

const routes: Routes = [
  { path: '', component: LiveCompileComponent }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class GraphViewRoutingModule {
}
