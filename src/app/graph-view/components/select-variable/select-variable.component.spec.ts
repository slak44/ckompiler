import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SelectVariableComponent } from './select-variable.component';

describe('SelectVariableComponent', () => {
  let component: SelectVariableComponent;
  let fixture: ComponentFixture<SelectVariableComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ SelectVariableComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(SelectVariableComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
