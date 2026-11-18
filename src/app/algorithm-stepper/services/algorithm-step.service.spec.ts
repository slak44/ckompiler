import { TestBed } from '@angular/core/testing';

import { AlgorithmStepService } from './algorithm-step.service';

describe('AlgorithmStepService', () => {
  let service: AlgorithmStepService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AlgorithmStepService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
