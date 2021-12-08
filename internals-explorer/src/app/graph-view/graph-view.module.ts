import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewRoutingModule } from './graph-view-routing.module';
import { GraphViewComponent } from './graph-view.component';
import { BasicBlockComponent } from './components/basic-block/basic-block.component';

@NgModule({
  declarations: [
    GraphViewComponent,
    BasicBlockComponent
  ],
  imports: [
    CommonModule,
    GraphViewRoutingModule
  ]
})
export class GraphViewModule {
}
