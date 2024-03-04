import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PhiInsertionViewComponent } from './phi-insertion-view.component';

describe('PhiInsertionViewComponent', () => {
  let component: PhiInsertionViewComponent;
  let fixture: ComponentFixture<PhiInsertionViewComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ PhiInsertionViewComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(PhiInsertionViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
