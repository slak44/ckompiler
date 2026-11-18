import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InsertionAlgorithmComponent } from './insertion-algorithm.component';

describe('InsertionAlgorithmComponent', () => {
  let component: InsertionAlgorithmComponent;
  let fixture: ComponentFixture<InsertionAlgorithmComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ InsertionAlgorithmComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(InsertionAlgorithmComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
