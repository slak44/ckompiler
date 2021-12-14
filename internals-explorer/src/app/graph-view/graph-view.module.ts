import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewRoutingModule } from './graph-view-routing.module';
import { GraphViewComponent } from './graph-view.component';
import { HighlightModule } from 'ngx-highlightjs';
import { IrFragmentComponent } from './components/ir-fragment/ir-fragment.component';

@NgModule({
  declarations: [
    GraphViewComponent,
    IrFragmentComponent,
  ],
  imports: [
    CommonModule,
    HighlightModule,
    GraphViewRoutingModule,
  ],
})
export class GraphViewModule {
}
