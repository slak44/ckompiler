import { ComponentFixture, TestBed } from '@angular/core/testing';

import { GraphViewFragmentComponent } from './graph-view-fragment.component';

describe('IrFragmentComponent', () => {
  let component: GraphViewFragmentComponent;
  let fixture: ComponentFixture<GraphViewFragmentComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ GraphViewFragmentComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(GraphViewFragmentComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
