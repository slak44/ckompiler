import { Injectable } from '@angular/core';
import { TourService } from 'ngx-ui-tour-md-menu';
import { combineLatest, filter, merge, pairwise, takeUntil } from 'rxjs';
import { PhiInsertionState, PhiInsertionStateService } from './phi-insertion-state.service';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';

const STEP_STARTED = 'step-started';

const PHI_TOUR_STORAGE_KEY = 'phi-tour';

@Injectable()
export class PhiInsertionTourService extends SubscriptionDestroy {
  constructor(
    private tourService: TourService,
    private phiInsertionStateService: PhiInsertionStateService,
  ) {
    super();

    this.tourService.initialize([
      {
        title: 'φ Insertion UI tour',
        content: 'This tour will explain the interface and the controls of this interactive page. Click next to ' +
          'continue.',
        anchorId: 'anchor-center',
      },
      {
        title: 'φ Insertion UI tour',
        content: 'First, let\'s look at the basic blocks. They contain the final state of the IR, after inserting φ ' +
          'functions.',
        anchorId: 'anchor-start-block',
      },
      {
        title: 'φ Insertion UI tour',
        content: 'Now, select a variable from the dropdown menu.',
        anchorId: 'anchor-target-variable',
      },
      {
        title: 'φ Insertion UI tour',
        content: 'Notice how every use and every definition of the selected variable is now highlighted.',
        anchorId: 'anchor-start-block',
      },
      {
        title: 'φ Insertion UI tour',
        content: 'Press start to begin running the algorithm itself.',
        anchorId: 'anchor-start-button',
      },
      {
        stepId: STEP_STARTED,
        title: 'φ Insertion UI tour',
        content: 'All φ functions are now removed, so we can see how and where they\'re inserted.',
        anchorId: 'anchor-start-block',
      },
      {
        title: 'φ Insertion UI tour',
        content: 'On the left side, the current state of the algorithm is displayed. It will be updated as the ' +
          'algorithm runs.',
        anchorId: 'anchor-progress-overlay',
      },
      {
        title: 'φ Insertion UI tour',
        content: 'On the right side, the algorithm itself is presented, in pseudocode. The currently executing line ' +
          'is highlighted, and an arrow shows where the execution stopped.',
        anchorId: 'anchor-insertion-algorithm',
      },
      {
        title: 'φ Insertion UI tour',
        content: 'These are the algorithm controls. You can go forward, backward, or just select a certain step ' +
          'using the slider. For ease of use, the left and right arrows on the keyboard also go backwards and ' +
          'forwards.',
        anchorId: 'anchor-controls',
        enableBackdrop: true,
      },
      {
        title: 'φ Insertion UI tour',
        content: 'Now you can continue running the algorithm until its completion. To select another variable, click ' +
          'the reset button in the bottom right corner. Changing the source code will also reset the graph.',
        anchorId: 'anchor-center',
      },
    ]);
  }

  public start(): void {
    if (localStorage.getItem(PHI_TOUR_STORAGE_KEY) === 'true') {
      // Already viewed tour
      return;
    }

    combineLatest([
      this.tourService.stepShow$,
      this.phiInsertionStateService.phiInsertionState$,
    ]).pipe(
      filter(([stepShow, state]) => stepShow.stepId === STEP_STARTED && state !== PhiInsertionState.WORKLOOP),
      takeUntil(this.destroy$),
    ).subscribe(() => this.phiInsertionStateService.startInsertion());

    this.phiInsertionStateService.phiInsertionState$.pipe(
      pairwise(),
      filter(([prev, curr]) => prev === PhiInsertionState.CONFIGURE && curr === PhiInsertionState.WORKLOOP),
      takeUntil(this.destroy$),
    ).subscribe(() => this.tourService.goto(STEP_STARTED));

    this.tourService.end$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(() => localStorage.setItem(PHI_TOUR_STORAGE_KEY, 'true'));

    this.tourService.start();
  }
}
