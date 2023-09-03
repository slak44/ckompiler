import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActiveBroadcastBannerComponent } from './components/active-broadcast-banner/active-broadcast-banner.component';

@NgModule({
  declarations: [ActiveBroadcastBannerComponent],
  exports: [ActiveBroadcastBannerComponent],
  imports: [CommonModule],
})
export class BroadcastModule {
}
