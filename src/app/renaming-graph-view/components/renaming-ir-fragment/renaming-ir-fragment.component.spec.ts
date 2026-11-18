import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RenamingIrFragmentComponent } from './renaming-ir-fragment.component';

describe('RenamingIrFragmentComponent', () => {
  let component: RenamingIrFragmentComponent;
  let fixture: ComponentFixture<RenamingIrFragmentComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ RenamingIrFragmentComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(RenamingIrFragmentComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
