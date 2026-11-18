import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BroadcasterMouseComponent } from './broadcaster-mouse.component';

describe('BroadcasterMouseComponent', () => {
  let component: BroadcasterMouseComponent;
  let fixture: ComponentFixture<BroadcasterMouseComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [BroadcasterMouseComponent]
    });
    fixture = TestBed.createComponent(BroadcasterMouseComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
