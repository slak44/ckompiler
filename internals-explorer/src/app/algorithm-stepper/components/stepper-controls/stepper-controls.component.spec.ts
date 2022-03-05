import { ComponentFixture, TestBed } from '@angular/core/testing';

import { StepperControlsComponent } from './stepper-controls.component';

describe('StepperControlsComponent', () => {
  let component: StepperControlsComponent;
  let fixture: ComponentFixture<StepperControlsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ StepperControlsComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(StepperControlsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
