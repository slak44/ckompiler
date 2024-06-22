import { ComponentFixture, TestBed } from '@angular/core/testing';

import { LiveCompileComponent } from './live-compile.component';

describe('LiveCompileComponent', () => {
  let component: LiveCompileComponent;
  let fixture: ComponentFixture<LiveCompileComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ LiveCompileComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(LiveCompileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
