import { ComponentFixture, TestBed } from '@angular/core/testing';

import { VarRenameViewComponent } from './var-rename-view.component';

describe('VarRenameViewComponent', () => {
  let component: VarRenameViewComponent;
  let fixture: ComponentFixture<VarRenameViewComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ VarRenameViewComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(VarRenameViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
