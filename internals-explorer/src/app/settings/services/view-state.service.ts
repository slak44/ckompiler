import { Injectable } from '@angular/core';
import {
  BehaviorSubject,
  combineLatest,
  debounceTime,
  distinctUntilChanged,
  filter,
  first,
  map,
  Observable,
  shareReplay,
  startWith,
  switchMap,
  takeUntil,
  withLatestFrom,
} from 'rxjs';
import { hasEqualViewStates, ViewState, ViewStateListing, ZoomTransformDto } from '../models/view-state.model';
import { ViewStateApiService } from './view-state-api.service';
import {
  currentPrintingType,
  currentTargetFunction,
  graphViewSelectedId,
  graphViewTransform,
  hideGraphUI,
  isaType,
  isSpillOnly,
  phiInsertionSelectedId,
  phiInsertionStepIdx,
  phiInsertionTransform,
  phiInsertionVariableId,
  Setting,
  sourceCode,
  variableRenameSelectedId,
  variableRenameStepIdx,
  variableRenameTransform,
  variableRenameVariableId,
} from '@cki-settings';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { UserStateService } from './user-state.service';
import { CodePrintingMethods, ISAType } from '@ckompiler/ckompiler';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { NavigationEnd, Router } from '@angular/router';
import { ZoomTransform } from 'd3-zoom';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AuthService } from '@auth0/auth0-angular';
import { subscribeIfAuthenticated } from '@cki-utils/subscribe-if-authenticated';

@Injectable({
  providedIn: 'root',
})
export class ViewStateService extends SubscriptionDestroy {
  private readonly stateLockSubject: BehaviorSubject<boolean> = new BehaviorSubject<boolean>(false);
  public readonly stateLock$: Observable<boolean> = this.stateLockSubject;

  private readonly viewStatesSubject: BehaviorSubject<ViewStateListing[]> = new BehaviorSubject<ViewStateListing[]>([]);
  public readonly viewStates$: Observable<ViewStateListing[]> = this.viewStatesSubject.pipe(
    map(viewStates => viewStates.sort((a, b) => {
      if (a.createdAt > b.createdAt) {
        return -1;
      } else {
        return 1;
      }
    })),
  );

  private readonly navigationUrl$: Observable<string> = this.router.events.pipe(
    filter((event): event is NavigationEnd => event instanceof NavigationEnd),
    map(event => event.urlAfterRedirects),
    startWith(this.router.url),
    distinctUntilChanged(),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  private readonly viewStateData$: Observable<Omit<ViewState, 'id' | 'name' | 'createdAt'>> = combineLatest([
    sourceCode.value$,
    isaType.value$,
    hideGraphUI.value$,
    isSpillOnly.value$,
    currentTargetFunction.value$,
    currentPrintingType.value$,
    graphViewTransform.value$,
    graphViewSelectedId.value$,
    phiInsertionTransform.value$,
    phiInsertionSelectedId.value$,
    phiInsertionVariableId.value$,
    phiInsertionStepIdx.value$.pipe(
      map(step => step ?? 0),
    ),
    variableRenameTransform.value$,
    variableRenameSelectedId.value$,
    variableRenameVariableId.value$,
    variableRenameStepIdx.value$.pipe(
      map(step => step ?? 0),
    ),
    this.navigationUrl$,
  ]).pipe(
    map(([
      sourceCode,
      isaType,
      hideGraphUI,
      isSpillOnly,
      currentTargetFunction,
      printingType,
      graphViewTransform,
      graphViewSelectedId,
      phiInsertionTransform,
      phiInsertionSelectedId,
      phiInsertionVariableId,
      phiInsertionStepIdx,
      variableRenameTransform,
      variableRenameSelectedId,
      variableRenameVariableId,
      variableRenameStepIdx,
      navigationEndUrl,
    ]) => {
      return {
        activeRoute: navigationEndUrl,
        graphViewState: {
          isUiHidden: hideGraphUI,
          isSpillOnly,
          targetFunction: currentTargetFunction,
          printingType: printingType.name,
          transform: graphViewTransform ? {
            k: graphViewTransform.k,
            x: graphViewTransform.x,
            y: graphViewTransform.y,
          } : null,
          selectedNodeId: graphViewSelectedId,
        },
        phiInsertionViewState: {
          targetVariable: phiInsertionVariableId,
          currentStep: phiInsertionStepIdx,
          transform: phiInsertionTransform ? {
            k: phiInsertionTransform.k,
            x: phiInsertionTransform.x,
            y: phiInsertionTransform.y,
          } : null,
          selectedNodeId: phiInsertionSelectedId,
        },
        variableRenameViewState: {
          targetVariable: variableRenameVariableId,
          currentStep: variableRenameStepIdx,
          transform: variableRenameTransform ? {
            k: variableRenameTransform.k,
            x: variableRenameTransform.x,
            y: variableRenameTransform.y,
          } : null,
          selectedNodeId: variableRenameSelectedId,
        },
        isaType: isaType.name,
        owner: null,
        sourceCode,
      };
    }),
  );

  private readonly storeViewState$: Observable<ViewState | null> = this.viewStatesSubject.pipe(
    filter(viewStates => viewStates.length > 0),
    withLatestFrom(this.viewStateData$),
    map(([viewStates, viewStateData]): ViewState | null => {
      const state = viewStates.find(state => state.id === null);
      if (!state) {
        return null;
      }

      return {
        ...viewStateData,
        id: state.id,
        name: state.name,
        createdAt: null,
      };
    }),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  private readonly latestAutosave$: Observable<ViewState> = subscribeIfAuthenticated(this.authService).pipe(
    switchMap(() => this.userStateService.getCurrentState()),
    map(userState => userState.autosaveViewState),
    filter((autosave): autosave is ViewState => !!autosave),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  constructor(
    private readonly viewStateApiService: ViewStateApiService,
    private readonly userStateService: UserStateService,
    private readonly compileService: CompileService,
    private readonly router: Router,
    private readonly authService: AuthService,
    private readonly snackBar: MatSnackBar,
  ) {
    super();
  }

  private suggestLoadingAutosave(): void {
    combineLatest([
      this.latestAutosave$.pipe(first()),
      this.viewStateData$.pipe(first()),
    ]).pipe(
      filter(([autosave, stored]) => {
        const autosaveCopy = { ...autosave, id: null, name: '', owner: null, createdAt: null };
        const storedCopy = { ...stored, id: null, name: '', owner: null, createdAt: null };
        return !hasEqualViewStates(autosaveCopy, storedCopy);
      }),
      map(([autosave]) => autosave),
      takeUntil(this.destroy$),
    ).subscribe({
      next: (autosave) => {
        const message = 'Your local state is different from your autosave. Load remote autosave?';
        const snackbarRef = this.snackBar.open(message, 'LOAD');
        snackbarRef.onAction().pipe(
          takeUntil(this.destroy$),
        ).subscribe(() => {
          this.lockState();
          this.restoreState(autosave);
          this.unlockState();
        });
      },
      error: error => console.error(error),
    });
  }

  public refreshViewStates(): void {
    subscribeIfAuthenticated(this.authService).pipe(
      switchMap(() => this.viewStateApiService.getList()),
      takeUntil(this.destroy$),
    ).subscribe({
      next: viewStateList => {
        this.viewStatesSubject.next(viewStateList);
        this.suggestLoadingAutosave();
      },
      error: error => {
        console.error(error);
        this.snackBar.open('Failed to load saved states.');
      },
    });

    subscribeIfAuthenticated(this.authService).pipe(
      switchMap(() => this.viewStateData$),
      debounceTime(3000),
      map((viewStateData): ViewState => ({
        ...viewStateData,
        id: null,
        name: 'Autosave',
        createdAt: null,
      })),
      switchMap(viewState => this.viewStateApiService.saveAutosave(viewState)),
      takeUntil(this.destroy$),
    ).subscribe({
      error: error => console.error(error),
    });
  }

  public saveCurrentState(name: string): void {
    this.lockState();

    const viewStates = [...this.viewStatesSubject.value];
    const newListing: ViewStateListing = { id: null, name, createdAt: new Date().toISOString() };
    this.viewStatesSubject.next([...viewStates, newListing]);

    this.storeViewState$.pipe(
      first(),
      filter((viewState): viewState is ViewState => !!viewState),
      switchMap(viewState => this.viewStateApiService.save(viewState)),
      takeUntil(this.destroy$),
    ).subscribe({
      next: newViewState => {
        const viewStates = [...this.viewStatesSubject.value];
        const stateToUpdate = viewStates.find(state => state.id === null);
        if (stateToUpdate) {
          stateToUpdate.id = newViewState.id;
          this.viewStatesSubject.next(viewStates);
        }
      },
      error: error => console.error(error),
      complete: () => this.unlockState(),
    });
  }

  public fetchAndRestoreState(stateId: string): void {
    this.lockState();

    this.viewStateApiService.getById(stateId).subscribe({
      next: viewState => {
        this.restoreState(viewState);
      },
      error: error => {
        console.error(error);
        this.unlockState();
      },
    });
  }

  public deleteState(stateId: string): void {
    this.viewStateApiService.deleteById(stateId).subscribe(() => {
      const newStates = this.viewStatesSubject.value.filter(state => state.id !== stateId);
      this.viewStatesSubject.next(newStates);
    });
  }

  private restoreTransform(setting: Setting<ZoomTransform | null>, transform: ZoomTransformDto | null): void {
    if (transform) {
      const { k, x, y } = transform;
      setting.update(new ZoomTransform(k, x, y));
    } else {
      setting.update(null);
    }
  }

  private restoreState(viewState: ViewState): void {
    if (this.checkStateLockInvalid()) {
      return;
    }

    this.restoreTransform(graphViewTransform, viewState.graphViewState.transform);
    graphViewSelectedId.update(viewState.graphViewState.selectedNodeId);

    this.restoreTransform(phiInsertionTransform, viewState.phiInsertionViewState.transform);
    phiInsertionSelectedId.update(viewState.phiInsertionViewState.selectedNodeId);
    phiInsertionVariableId.update(viewState.phiInsertionViewState.targetVariable);
    phiInsertionStepIdx.update(viewState.phiInsertionViewState.currentStep);

    this.restoreTransform(variableRenameTransform, viewState.variableRenameViewState.transform);
    variableRenameSelectedId.update(viewState.variableRenameViewState.selectedNodeId);
    variableRenameVariableId.update(viewState.variableRenameViewState.targetVariable);
    variableRenameVariableId.update(viewState.variableRenameViewState.currentStep);

    isSpillOnly.update(viewState.graphViewState.isSpillOnly);
    hideGraphUI.update(viewState.graphViewState.isUiHidden);
    currentPrintingType.update(CodePrintingMethods.valueOf(viewState.graphViewState.printingType));
    currentTargetFunction.update(viewState.graphViewState.targetFunction);
    isaType.update(ISAType.valueOf(viewState.isaType));
    sourceCode.update(viewState.sourceCode);

    // Wait for compilation, then wait for change detection, and THEN change the route
    this.compileService.defaultCompileResult$.pipe(first(), takeUntil(this.destroy$)).subscribe({
      next: () => {
        setTimeout(() => {
          this.router.navigateByUrl(viewState.activeRoute)
            .then(() => this.unlockState())
            .catch(() => this.unlockState());
        }, 0);
      },
      error: error => {
        console.error(error);
        this.unlockState();
      },
    });
  }

  private lockState(): void {
    if (this.stateLockSubject.value) {
      console.error('Overlapping view state action detected. Skipping current action because state is locked');
      return;
    }
    this.stateLockSubject.next(true);
  }

  private unlockState(): void {
    this.stateLockSubject.next(false);
  }

  private checkStateLockInvalid(): boolean {
    if (!this.stateLockSubject.value) {
      console.error('Do not call this function without the state lock. This avoids interleaving restores.');
      return true;
    }

    return false;
  }
}
