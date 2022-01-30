import { ComponentFixture, TestBed } from '@angular/core/testing';

import { GraphUiOverlayComponent } from './graph-ui-overlay.component';

describe('GraphUiOverlayComponent', () => {
  let component: GraphUiOverlayComponent;
  let fixture: ComponentFixture<GraphUiOverlayComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ GraphUiOverlayComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(GraphUiOverlayComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
