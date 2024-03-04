import { TestBed } from '@angular/core/testing';

import { RenamingStateService } from './renaming-state.service';

describe('RenamingStateService', () => {
  let service: RenamingStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(RenamingStateService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
