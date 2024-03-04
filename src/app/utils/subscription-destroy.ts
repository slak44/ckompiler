import { Injectable, OnDestroy } from '@angular/core';
import { Observable, Subject } from 'rxjs';

@Injectable()
export class SubscriptionDestroy implements OnDestroy {
  private readonly destroySubject: Subject<void> = new Subject<void>();
  public readonly destroy$: Observable<void> = this.destroySubject;

  public ngOnDestroy(): void {
    this.destroySubject.next();
    this.destroySubject.complete();
  }
}
