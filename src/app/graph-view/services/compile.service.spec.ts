import { TestBed } from '@angular/core/testing';

import { CompileService } from './compile.service';

describe('CompileService', () => {
  let service: CompileService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(CompileService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
