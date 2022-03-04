import { ComponentFixture, TestBed } from '@angular/core/testing';

import { VarComponent } from './var.component';

describe('VarComponent', () => {
  let component: VarComponent;
  let fixture: ComponentFixture<VarComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ VarComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(VarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
