import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BroadcasterMouseWrapperComponent } from './broadcaster-mouse-wrapper.component';

describe('BroadcasterMouseWrapperComponent', () => {
  let component: BroadcasterMouseWrapperComponent;
  let fixture: ComponentFixture<BroadcasterMouseWrapperComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [BroadcasterMouseWrapperComponent]
    });
    fixture = TestBed.createComponent(BroadcasterMouseWrapperComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
