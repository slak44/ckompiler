import {
  AfterContentChecked,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  HostBinding,
  Input,
  OnInit,
} from '@angular/core';
import { map, Observable, takeUntil } from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { range, sumBy } from 'lodash-es';
import { phaseInOut } from '@cki-utils/phase-in-out';
import { hasTransparency } from '@cki-settings';

@Component({
  selector: 'cki-algorithm-container',
  templateUrl: './algorithm-container.component.html',
  styleUrls: ['./algorithm-container.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [phaseInOut],
})
export class AlgorithmContainerComponent extends SubscriptionDestroy implements OnInit, AfterContentChecked {
  @HostBinding('class.disable-transparency')
  private hasTransparencyDisabled!: boolean;

  @HostBinding('style.--current-line')
  private currentLine?: number;

  @HostBinding('@phaseInOut')
  private readonly animation: boolean = true;

  @Input()
  public activeStep$!: Observable<number>;

  public readonly stepToLineCount: Record<number, number> = {};
  public readonly stepToOffset: Record<number, number> = {};

  constructor(
    private changeDetectorRef: ChangeDetectorRef,
  ) {
    super();
  }

  public ngOnInit(): void {
    hasTransparency.value$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(hasTransparency => {
      this.hasTransparencyDisabled = !hasTransparency;
      this.changeDetectorRef.markForCheck();
    });
  }

  public ngAfterContentChecked(): void {
    this.activeStep$.pipe(
      map(step => sumBy(range(1, step + 1), item => this.stepToLineCount[item]) - this.stepToOffset[step]),
      takeUntil(this.destroy$),
    ).subscribe(currentLineCount => {
      this.currentLine = currentLineCount;
    });
  }
}
