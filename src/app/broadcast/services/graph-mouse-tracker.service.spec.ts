import { TestBed } from '@angular/core/testing';

import { GraphMouseTrackerService } from './graph-mouse-tracker.service';

describe('GraphMouseTrackerService', () => {
  let service: GraphMouseTrackerService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(GraphMouseTrackerService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
