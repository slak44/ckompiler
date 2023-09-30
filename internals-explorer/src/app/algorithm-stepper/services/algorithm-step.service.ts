import { Inject, Injectable, InjectionToken } from '@angular/core';
import { BehaviorSubject, combineLatestWith, map, Observable, OperatorFunction, pipe, shareReplay } from 'rxjs';
import { clamp } from 'lodash-es';
import { Setting } from '@cki-settings';

export const STEP_IDX_SETTING: InjectionToken<Setting<number | null>> = new InjectionToken('current-step-idx-setting');

export enum AlgorithmPhase {
  PREPARING,
  RUNNING
}

@Injectable()
export class AlgorithmStepService {
  private readonly phaseSubject: BehaviorSubject<AlgorithmPhase> =
    new BehaviorSubject<AlgorithmPhase>(AlgorithmPhase.PREPARING);

  public readonly phase$: Observable<AlgorithmPhase> = this.phaseSubject;

  public readonly currentStep$: Observable<number> = this.stepIdxSetting.value$.pipe(
    map((value, emissionIndex) => {
      const stepIdx = value ?? 0;

      if (emissionIndex === 0 && stepIdx !== 0) {
        setTimeout(() => this.phaseSubject.next(AlgorithmPhase.RUNNING));
      }

      return stepIdx;
    }),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  constructor(@Inject(STEP_IDX_SETTING) private readonly stepIdxSetting: Setting<number | null>) {
  }

  private getCurrentStepValue(): number {
    return this.stepIdxSetting.formControl.value ?? 0;
  }

  public start(): void {
    this.phaseSubject.next(AlgorithmPhase.RUNNING);
    this.stepIdxSetting.update(0);
  }

  public reset(): void {
    this.phaseSubject.next(AlgorithmPhase.PREPARING);
  }

  public nextStep(): void {
    if (this.phaseSubject.value !== AlgorithmPhase.RUNNING) {
      return;
    }
    this.stepIdxSetting.update(this.getCurrentStepValue() + 1);
  }

  public prevStep(): void {
    if (this.phaseSubject.value !== AlgorithmPhase.RUNNING) {
      return;
    }
    this.stepIdxSetting.update(this.getCurrentStepValue() - 1);
  }

  public setStep(value: number): void {
    if (this.phaseSubject.value !== AlgorithmPhase.RUNNING) {
      return;
    }
    this.stepIdxSetting.update(value);
  }

  public mapToCurrentStep<T>(): OperatorFunction<T[], T> {
    return pipe(
      combineLatestWith(this.currentStep$),
      map(([steps, index]) => {
        const clamped = clamp(index, 0, steps.length - 1);
        if (clamped !== index) {
          this.stepIdxSetting.update(clamped);
        }

        return steps[clamped];
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
  }
}
