import { TestBed } from '@angular/core/testing';

import { PhiInsertionStateService } from './phi-insertion-state.service';

describe('PhiInsertionStateService', () => {
  let service: PhiInsertionStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(PhiInsertionStateService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
