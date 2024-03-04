import { TestBed } from '@angular/core/testing';

import { InitialUserStateService } from './initial-user-state.service';

describe('InitialUserStateService', () => {
  let service: InitialUserStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(InitialUserStateService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
