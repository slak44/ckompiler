import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewRoutingModule } from './graph-view-routing.module';
import { GraphViewComponent } from './graph-view.component';
import { HighlightModule } from 'ngx-highlightjs';
import { IrFragmentComponent } from './components/ir-fragment/ir-fragment.component';
import { LiveCompileComponent } from './components/live-compile/live-compile.component';

@NgModule({
  declarations: [
    GraphViewComponent,
    IrFragmentComponent,
    LiveCompileComponent
  ],
  imports: [
    CommonModule,
    HighlightModule,
    GraphViewRoutingModule,
  ],
})
export class GraphViewModule {
}
