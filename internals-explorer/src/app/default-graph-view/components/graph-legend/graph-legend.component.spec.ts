import { ComponentFixture, TestBed } from '@angular/core/testing';

import { GraphLegendComponent } from './graph-legend.component';

describe('GraphLegendComponent', () => {
  let component: GraphLegendComponent;
  let fixture: ComponentFixture<GraphLegendComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ GraphLegendComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(GraphLegendComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
