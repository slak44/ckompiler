import { ComponentFixture, TestBed } from '@angular/core/testing';

import { GraphOptionsComponent } from './graph-options.component';

describe('GraphOptionsComponent', () => {
  let component: GraphOptionsComponent;
  let fixture: ComponentFixture<GraphOptionsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ GraphOptionsComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(GraphOptionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
