import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';

import { GraphViewRoutingModule } from './graph-view-routing.module';
import { GraphViewComponent } from './graph-view.component';


@NgModule({
  declarations: [
    GraphViewComponent
  ],
  imports: [
    CommonModule,
    GraphViewRoutingModule
  ]
})
export class GraphViewModule { }
