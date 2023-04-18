import { TestBed } from '@angular/core/testing';

import { AuthCallbackGuard } from './auth-callback.guard';

describe('AuthCallbackGuard', () => {
  let guard: AuthCallbackGuard;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    guard = TestBed.inject(AuthCallbackGuard);
  });

  it('should be created', () => {
    expect(guard).toBeTruthy();
  });
});
