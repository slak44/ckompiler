import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PhiIrFragmentComponent } from './phi-ir-fragment.component';

describe('PhiIrFragmentComponent', () => {
  let component: PhiIrFragmentComponent;
  let fixture: ComponentFixture<PhiIrFragmentComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ PhiIrFragmentComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(PhiIrFragmentComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
