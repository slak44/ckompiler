import { TestBed } from '@angular/core/testing';

import { BroadcastViewStateService } from './broadcast-view-state.service';

describe('BroadcastViewStateService', () => {
  let service: BroadcastViewStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(BroadcastViewStateService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
