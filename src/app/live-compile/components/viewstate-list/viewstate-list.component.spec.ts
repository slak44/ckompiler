import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ViewstateListComponent } from './viewstate-list.component';

describe('ViewstateListComponent', () => {
  let component: ViewstateListComponent;
  let fixture: ComponentFixture<ViewstateListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ ViewstateListComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(ViewstateListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
