import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RoutedTabGroupDirective } from './directives/routed-tab-group.directive';
import { RoutedTabDirective } from './directives/routed-tab.directive';

@NgModule({
  declarations: [
    RoutedTabGroupDirective,
    RoutedTabDirective,
  ],
  exports: [
    RoutedTabDirective,
    RoutedTabGroupDirective,
  ],
  imports: [
    CommonModule,
  ],
})
export class TabRoutingModule { }
