import { ComponentFixture, TestBed } from '@angular/core/testing';

import { IrFragmentComponent } from './ir-fragment.component';

describe('IrFragmentComponent', () => {
  let component: IrFragmentComponent;
  let fixture: ComponentFixture<IrFragmentComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ IrFragmentComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(IrFragmentComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
