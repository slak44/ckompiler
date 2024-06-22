import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ShareViewstateDialogComponent } from './share-viewstate-dialog.component';

describe('ShareViewstateDialogComponent', () => {
  let component: ShareViewstateDialogComponent;
  let fixture: ComponentFixture<ShareViewstateDialogComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ ShareViewstateDialogComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(ShareViewstateDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
