import { ChangeDetectionStrategy, ChangeDetectorRef, Component, HostBinding, Input, OnInit } from '@angular/core';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { takeUntil } from 'rxjs';
import { AlgorithmContainerComponent } from '../algorithm-container/algorithm-container.component';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'cki-algorithm-step',
  templateUrl: './algorithm-step.component.html',
  styleUrls: ['./algorithm-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
  ],
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
    private readonly algorithmContainer: AlgorithmContainerComponent,
    private readonly changeDetectorRef: ChangeDetectorRef,
  ) {
    super();
  }

  public ngOnInit(): void {
    this.algorithmContainer.stepToLineCount[this.stepIndex] = this.lines || 1;
    this.algorithmContainer.stepToOffset[this.stepIndex] = this.offsetLines;

    this.algorithmContainer.activeStep$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(activeStep => {
      this.isActive = this.stepIndex === activeStep;
      this.changeDetectorRef.markForCheck();
    });
  }
}
