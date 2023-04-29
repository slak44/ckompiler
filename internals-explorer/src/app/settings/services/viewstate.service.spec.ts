import { TestBed } from '@angular/core/testing';

import { ViewstateService } from './viewstate.service';

describe('ViewstateService', () => {
  let service: ViewstateService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ViewstateService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
