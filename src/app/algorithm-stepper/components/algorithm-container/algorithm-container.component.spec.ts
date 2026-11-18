import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AlgorithmContainerComponent } from './algorithm-container.component';

describe('AlgorithmContainerComponent', () => {
  let component: AlgorithmContainerComponent;
  let fixture: ComponentFixture<AlgorithmContainerComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ AlgorithmContainerComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(AlgorithmContainerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
