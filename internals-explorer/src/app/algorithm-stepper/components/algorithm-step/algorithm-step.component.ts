import { ChangeDetectionStrategy, ChangeDetectorRef, Component, HostBinding, Input, OnInit } from '@angular/core';
import { AlgorithmContainerDirective } from '../../directives/algorithm-container.directive';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { takeUntil } from 'rxjs';

@Component({
  selector: 'cki-algorithm-step',
  templateUrl: './algorithm-step.component.html',
  styleUrls: ['./algorithm-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlgorithmStepComponent extends SubscriptionDestroy implements OnInit {
  @HostBinding('class.active')
  private isActive: boolean = false;

  @Input()
  public stepIndex!: number;

  @Input()
  public lines: number = 1;

  @Input()
  @HostBinding('style.--offset-lines')
  public offsetLines: number = 0;

  constructor(
    private algorithmContainer: AlgorithmContainerDirective,
    private changeDetectorRef: ChangeDetectorRef,
  ) {
    super();
  }

  public ngOnInit(): void {
    this.algorithmContainer.stepToLineCount[this.stepIndex] = this.lines || 1;
    this.algorithmContainer.stepToOffset[this.stepIndex] = this.offsetLines;

    this.algorithmContainer.activeStep$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(activeStep => {
      this.isActive = this.stepIndex === activeStep;
      this.changeDetectorRef.markForCheck();
    });
  }
}
