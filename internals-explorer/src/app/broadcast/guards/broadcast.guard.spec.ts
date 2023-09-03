import { TestBed } from '@angular/core/testing';

import { BroadcastGuard } from './broadcast.guard';

describe('BroadcastGuard', () => {
  let guard: BroadcastGuard;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    guard = TestBed.inject(BroadcastGuard);
  });

  it('should be created', () => {
    expect(guard).toBeTruthy();
  });
});
