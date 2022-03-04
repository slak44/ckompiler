import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AlgorithmStepComponent } from './algorithm-step.component';

describe('AlgorithmStepComponent', () => {
  let component: AlgorithmStepComponent;
  let fixture: ComponentFixture<AlgorithmStepComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ AlgorithmStepComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(AlgorithmStepComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
