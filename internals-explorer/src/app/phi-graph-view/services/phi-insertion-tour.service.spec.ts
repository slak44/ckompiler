import { TestBed } from '@angular/core/testing';

import { PhiInsertionTourService } from './phi-insertion-tour.service';

describe('PhiInsertionTourService', () => {
  let service: PhiInsertionTourService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(PhiInsertionTourService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
