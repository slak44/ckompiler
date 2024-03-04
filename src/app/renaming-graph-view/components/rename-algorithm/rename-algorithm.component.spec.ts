import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RenameAlgorithmComponent } from './rename-algorithm.component';

describe('RenameAlgorithmComponent', () => {
  let component: RenameAlgorithmComponent;
  let fixture: ComponentFixture<RenameAlgorithmComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ RenameAlgorithmComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(RenameAlgorithmComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
