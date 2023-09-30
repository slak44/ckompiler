import { Injectable } from '@angular/core';
import {
  BehaviorSubject,
  combineLatest,
  debounceTime,
  distinctUntilChanged,
  EMPTY,
  filter,
  first,
  map,
  Observable,
  of,
  OperatorFunction,
  pipe,
  shareReplay,
  startWith,
  switchMap,
  takeUntil,
  tap,
  withLatestFrom,
} from 'rxjs';
import {
  hasEqualViewStates,
  ViewState,
  ViewStateListing,
  ViewStateNonMetadata,
  ViewStateNonMetadataDelta,
  wipeMetadataFromState,
  ZoomTransformDto,
} from '../models/view-state.model';
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
import { CodePrintingMethods, ISAType } from '@ckompiler/ckompiler';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { NavigationEnd, Router } from '@angular/router';
import { ZoomTransform } from 'd3-zoom';
import { MatLegacySnackBar as MatSnackBar } from '@angular/material/legacy-snack-bar';
import { AuthService } from '@auth0/auth0-angular';
import { subscribeIfAuthenticated } from '@cki-utils/subscribe-if-authenticated';
import { InitialUserStateService } from './initial-user-state.service';

@Injectable({
  providedIn: 'root',
})
export class ViewStateService extends SubscriptionDestroy {
  private isAutosaveEnabled: boolean = true;

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

  public readonly viewStateData$: Observable<ViewStateNonMetadata> = combineLatest([
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
    shareReplay({ bufferSize: 1, refCount: false }),
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
        owner: null,
        createdAt: null,
        publicShareEnabled: state.publicShareEnabled,
      };
    }),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  private readonly latestAutosave$: Observable<ViewState> = this.initialUserStateService.initialUserState$.pipe(
    map(userState => userState.autosaveViewState),
    filter((autosave): autosave is ViewState => !!autosave),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  constructor(
    private readonly viewStateApiService: ViewStateApiService,
    private readonly compileService: CompileService,
    private readonly router: Router,
    private readonly authService: AuthService,
    private readonly snackBar: MatSnackBar,
    private readonly initialUserStateService: InitialUserStateService,
  ) {
    super();
  }

  private suggestLoadingAutosave(): void {
    combineLatest([
      this.latestAutosave$.pipe(first()),
      this.viewStateData$.pipe(first()),
    ]).pipe(
      filter(([autosave, stored]) => {
        const autosaveCopy = wipeMetadataFromState(autosave);
        const storedCopy = wipeMetadataFromState(stored);
        return !hasEqualViewStates(autosaveCopy, storedCopy);
      }),
      map(([autosave]) => autosave),
      takeUntil(this.destroy$),
    ).subscribe({
      next: (autosave) => {
        if (!this.isAutosaveEnabled) {
          // Don't show this message if autosaving is disabled
          return;
        }

        const message = 'Your local state is different from your autosave. Load remote autosave?';
        const snackbarRef = this.snackBar.open(message, 'LOAD');
        snackbarRef.onAction().pipe(
          tap(() => this.lockState()),
          switchMap(() => this.restoreState(autosave)),
          this.navigateByRoute(),
          takeUntil(this.destroy$),
        ).subscribe({
          next: () => this.unlockState(),
          error: error => {
            console.error(error);
            this.unlockState();
          },
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
        owner: null,
        createdAt: null,
        publicShareEnabled: false,
      })),
      switchMap(viewState => {
        if (!this.isAutosaveEnabled) {
          return EMPTY;
        }

        return this.viewStateApiService.saveAutosave(viewState);
      }),
      takeUntil(this.destroy$),
    ).subscribe({
      error: error => console.error(error),
    });
  }

  public setAutosaveEnabledState(isEnabled: boolean): void {
    this.isAutosaveEnabled = isEnabled;
  }

  public saveCurrentState(name: string): void {
    this.lockState();

    const viewStates = [...this.viewStatesSubject.value];
    const newListing: ViewStateListing = {
      id: null,
      name,
      createdAt: new Date().toISOString(),
      publicShareEnabled: false,
    };
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

  public fetchAndRestoreState(stateId: string, skipNavigation?: boolean): Observable<ViewState> {
    this.lockState();

    return this.viewStateApiService.getById(stateId).pipe(
      switchMap((viewState) => this.restoreState(viewState).pipe(
        skipNavigation ? map(() => void null) : this.navigateByRoute(),
        map(() => viewState)
      )),
      tap({
        next: () => this.unlockState(),
        error: () => this.unlockState(),
      }),
    );
  }

  public deleteState(stateId: string): void {
    this.viewStateApiService.deleteById(stateId).subscribe(() => {
      const newStates = this.viewStatesSubject.value.filter(state => state.id !== stateId);
      this.viewStatesSubject.next(newStates);
    });
  }

  public configurePublicShare(stateId: string, isEnabled: boolean): Observable<void> {
    const idx = this.viewStatesSubject.value.findIndex(viewState => viewState.id === stateId);
    if (idx === -1) {
      console.error('Cannot find view state with id ' + stateId);

      return of(void null);
    }

    return this.viewStateApiService.configurePublicShare(stateId, isEnabled).pipe(
      tap(() => {
        const newStates = [...this.viewStatesSubject.value];
        newStates[idx] = {
          ...newStates[idx],
          publicShareEnabled: isEnabled,
        };
        this.viewStatesSubject.next(newStates);
      }),
    );
  }

  private restoreTransform(setting: Setting<ZoomTransform | null>, transform: ZoomTransformDto | null): void {
    if (transform) {
      const { k, x, y } = transform;
      setting.update(new ZoomTransform(k, x, y));
    } else {
      setting.update(null);
    }
  }

  private restoreState(viewState: ViewStateNonMetadata): Observable<string | null> {
    if (this.checkStateLockInvalid()) {
      return of(null);
    }

    return this.restoreStateLockless(viewState);
  }

  private navigateByRoute(): OperatorFunction<string | null, void> {
    return switchMap(activeRoute => {
      if (activeRoute) {
        this.router.navigateByUrl(activeRoute).catch(error => console.error(error));
      }

      return of(void null);
    });
  }

  public restoreStateStream(): OperatorFunction<ViewStateNonMetadataDelta, void> {
    return pipe(
      switchMap(state => this.restoreStateLockless(state)),
      this.navigateByRoute(),
    );
  }

  private restoreStateLockless(viewState: ViewStateNonMetadataDelta): Observable<string | null> {
    this.restoreTransform(graphViewTransform, viewState.graphViewState.transform);
    graphViewSelectedId.update(viewState.graphViewState.selectedNodeId);

    this.restoreTransform(phiInsertionTransform, viewState.phiInsertionViewState.transform);
    phiInsertionSelectedId.update(viewState.phiInsertionViewState.selectedNodeId);
    phiInsertionVariableId.update(viewState.phiInsertionViewState.targetVariable);
    phiInsertionStepIdx.update(viewState.phiInsertionViewState.currentStep);

    this.restoreTransform(variableRenameTransform, viewState.variableRenameViewState.transform);
    variableRenameSelectedId.update(viewState.variableRenameViewState.selectedNodeId);
    variableRenameVariableId.update(viewState.variableRenameViewState.targetVariable);
    variableRenameStepIdx.update(viewState.variableRenameViewState.currentStep);

    isSpillOnly.update(viewState.graphViewState.isSpillOnly);
    hideGraphUI.update(viewState.graphViewState.isUiHidden);
    currentPrintingType.update(CodePrintingMethods.valueOf(viewState.graphViewState.printingType));
    currentTargetFunction.update(viewState.graphViewState.targetFunction);
    isaType.update(ISAType.valueOf(viewState.isaType));

    if (viewState.sourceCode) {
      sourceCode.update(viewState.sourceCode);
    }

    if (this.router.url === viewState.activeRoute) {
      return of(null);
    }

    // Wait for compilation before returning the route to change
    return this.compileService.defaultCompileResult$.pipe(
      first(),
      map(() => viewState.activeRoute),
      takeUntil(this.destroy$),
    );
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
