import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DefaultGraphViewComponent } from './default-graph-view.component';

describe('DefaultGraphViewComponent', () => {
  let component: DefaultGraphViewComponent;
  let fixture: ComponentFixture<DefaultGraphViewComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ DefaultGraphViewComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(DefaultGraphViewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
