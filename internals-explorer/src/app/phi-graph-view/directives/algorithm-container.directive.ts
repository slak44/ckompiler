import { AfterContentChecked, Directive, HostBinding, Input } from '@angular/core';
import { map, Observable, takeUntil } from 'rxjs';
import { range, sumBy } from 'lodash-es';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';

@Directive({
  selector: '[ckiAlgorithmContainer]',
})
export class AlgorithmContainerDirective extends SubscriptionDestroy implements AfterContentChecked {
  @HostBinding('style.--current-line')
  private currentLine?: number;

  @Input()
  public activeStep$!: Observable<number>;

  public readonly stepToLineCount: Record<number, number> = {};
  public readonly stepToOffset: Record<number, number> = {};

  constructor() {
    super();
  }

  public ngAfterContentChecked(): void {
    this.activeStep$.pipe(
      map(step => sumBy(range(1, step + 1), item => this.stepToLineCount[item]) - this.stepToOffset[step]),
      takeUntil(this.destroy$)
    ).subscribe(currentLineCount => {
      this.currentLine = currentLineCount;
    });
  }
}
