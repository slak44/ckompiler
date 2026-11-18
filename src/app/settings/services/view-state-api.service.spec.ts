import { TestBed } from '@angular/core/testing';

import { ViewStateApiService } from './view-state-api.service';

describe('ViewStateApiService', () => {
  let service: ViewStateApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ViewStateApiService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
