import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SelectFunctionComponent } from './select-function.component';

describe('SelectFunctionComponent', () => {
  let component: SelectFunctionComponent;
  let fixture: ComponentFixture<SelectFunctionComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ SelectFunctionComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(SelectFunctionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
