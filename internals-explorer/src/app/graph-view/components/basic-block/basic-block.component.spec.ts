import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BasicBlockComponent } from './basic-block.component';

describe('BasicBlockComponent', () => {
  let component: BasicBlockComponent;
  let fixture: ComponentFixture<BasicBlockComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ BasicBlockComponent ]
    })
    .compileComponents();
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(BasicBlockComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
