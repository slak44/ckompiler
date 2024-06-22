import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ActiveBroadcastBannerComponent } from './active-broadcast-banner.component';

describe('ActiveBroadcastBannerComponent', () => {
  let component: ActiveBroadcastBannerComponent;
  let fixture: ComponentFixture<ActiveBroadcastBannerComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ ActiveBroadcastBannerComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(ActiveBroadcastBannerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
