import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphViewComponent } from './components/graph-view/graph-view.component';
import { ResizeObserverModule } from '@ng-web-apis/resize-observer';

@NgModule({
  declarations: [
    GraphViewComponent,
  ],
  imports: [
    CommonModule,
    ResizeObserverModule,
  ],
  exports: [
    GraphViewComponent,
  ],
})
export class GraphViewModule {
}
