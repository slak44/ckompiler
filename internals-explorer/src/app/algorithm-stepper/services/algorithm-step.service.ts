import { Injectable } from '@angular/core';
import {
  BehaviorSubject,
  combineLatestWith,
  distinctUntilChanged,
  map,
  Observable,
  OperatorFunction,
  pipe,
  shareReplay,
} from 'rxjs';
import { clamp } from 'lodash-es';

export enum AlgorithmPhase {
  PREPARING,
  RUNNING
}

@Injectable()
export class AlgorithmStepService {
  private readonly phaseSubject: BehaviorSubject<AlgorithmPhase> =
    new BehaviorSubject<AlgorithmPhase>(AlgorithmPhase.PREPARING);

  public readonly phase$: Observable<AlgorithmPhase> = this.phaseSubject;

  private readonly currentStepSubject: BehaviorSubject<number> = new BehaviorSubject<number>(0);

  public readonly currentStep$: Observable<number> = this.currentStepSubject.pipe(
    distinctUntilChanged(),
  );

  constructor() {
  }

  public start(): void {
    this.phaseSubject.next(AlgorithmPhase.RUNNING);
    this.currentStepSubject.next(0);
  }

  public reset(): void {
    this.phaseSubject.next(AlgorithmPhase.PREPARING);
  }

  public nextStep(): void {
    if (this.phaseSubject.value !== AlgorithmPhase.RUNNING) {
      return;
    }
    this.currentStepSubject.next(this.currentStepSubject.value + 1);
  }

  public prevStep(): void {
    if (this.phaseSubject.value !== AlgorithmPhase.RUNNING) {
      return;
    }
    this.currentStepSubject.next(this.currentStepSubject.value - 1);
  }

  public setStep(value: number): void {
    if (this.phaseSubject.value !== AlgorithmPhase.RUNNING) {
      return;
    }
    this.currentStepSubject.next(value);
  }

  public mapToCurrentStep<T>(): OperatorFunction<T[], T> {
    return pipe(
      combineLatestWith(this.currentStep$),
      map(([steps, index]) => {
        const clamped = clamp(index, 0, steps.length - 1);
        if (clamped !== index) {
          this.currentStepSubject.next(clamped);
        }

        return steps[clamped];
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
  }
}
