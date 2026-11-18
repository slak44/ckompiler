import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { combineLatest, map, Observable, takeUntil, tap } from 'rxjs';
import { DiagnosticsStats, ISAType } from '@ckompiler/ckompiler';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { MatDialog } from '@angular/material/dialog';
import { SettingsDialogComponent } from '../../../settings/components/settings-dialog/settings-dialog.component';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { isaType } from '@cki-settings';
import { AuthService } from '@auth0/auth0-angular';
import { ViewStateService } from '../../../settings/services/view-state.service';
import { ActivatedRoute, Router } from '@angular/router';
import { CFG_PATH, DIAGNOSTICS_PATH, PHI_PATH, RENAME_PATH, SOURCE_CODE_PATH } from '@cki-utils/routes';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatTabsModule } from '@angular/material/tabs';
import { MatChipsModule } from '@angular/material/chips';
import {
  ActiveBroadcastBannerComponent,
} from '../../../broadcast/components/active-broadcast-banner/active-broadcast-banner.component';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RoutedTabGroupDirective } from '../../../tab-routing/directives/routed-tab-group.directive';
import { RoutedTabDirective } from '../../../tab-routing/directives/routed-tab.directive';
import {
  DefaultGraphViewComponent,
} from '../../../default-graph-view/components/default-graph-view/default-graph-view.component';
import {
  PhiInsertionViewComponent,
} from '../../../phi-graph-view/components/phi-insertion-view/phi-insertion-view.component';
import {
  VarRenameViewComponent,
} from '../../../renaming-graph-view/components/var-rename-view/var-rename-view.component';
import { SidenavContentComponent } from '../sidenav-content/sidenav-content.component';
import { SourceEditorComponent } from '../source-editor/source-editor.component';
import { DiagnosticListComponent } from '../diagnostic-list/diagnostic-list.component';
import { InitialUserStateService } from '../../../settings/services/initial-user-state.service';

@Component({
  selector: 'cki-live-compile',
  templateUrl: './live-compile.component.html',
  styleUrls: ['./live-compile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatProgressSpinnerModule,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    ReactiveFormsModule,
    MatSidenavModule,
    MatTabsModule,
    MatChipsModule,
    ActiveBroadcastBannerComponent,
    MatTooltipModule,
    RoutedTabGroupDirective,
    RoutedTabDirective,
    DefaultGraphViewComponent,
    PhiInsertionViewComponent,
    VarRenameViewComponent,
    SidenavContentComponent,
    SourceEditorComponent,
    DiagnosticListComponent,
  ],
})
export class LiveCompileComponent extends SubscriptionDestroy {
  public readonly SOURCE_CODE_PATH = SOURCE_CODE_PATH;
  public readonly DIAGNOSTICS_PATH = DIAGNOSTICS_PATH;
  public readonly CFG_PATH = CFG_PATH;
  public readonly PHI_PATH = PHI_PATH;
  public readonly RENAME_PATH = RENAME_PATH;

  public readonly isaTypeValues = ISAType.values();

  public readonly isaTypeControl: FormControl<ISAType> = isaType.formControl;

  public readonly isaType$: Observable<ISAType> = isaType.value$;

  public readonly initialSource$: Observable<string> =
    this.httpClient.get(this.location.prepareExternalUrl('/assets/samples/default.c'), { responseType: 'text' });

  public readonly diagnosticStats$: Observable<DiagnosticsStats> = this.compileService.diagnosticStats$;

  public readonly hasErrors$: Observable<boolean> = combineLatest([
    this.compileService.diagnosticStats$.pipe(map(stats => stats.errors > 0)),
    this.compileService.latestCrash$,
  ]).pipe(
    map(([hasErrors, latestCrash]) => hasErrors || !!latestCrash),
    tap(hasErrors => {
      const path = this.activatedRoute.snapshot.firstChild?.routeConfig?.path ?? '';
      if (hasErrors && ![SOURCE_CODE_PATH, DIAGNOSTICS_PATH].includes(path)) {
        this.router.navigate([DIAGNOSTICS_PATH], { relativeTo: this.activatedRoute })
          .catch(error => console.error(error));
      }
    }),
  );

  public readonly user$ = this.authService.user$;
  public readonly userState$ = this.initialUserStateService.initialUserState$;

  public readonly stateLock$: Observable<boolean> = this.viewStateService.stateLock$;

  constructor(
    private readonly httpClient: HttpClient,
    private readonly compileService: CompileService,
    private readonly location: Location,
    private readonly dialog: MatDialog,
    private readonly authService: AuthService,
    private readonly viewStateService: ViewStateService,
    private readonly router: Router,
    private readonly activatedRoute: ActivatedRoute,
    private readonly initialUserStateService: InitialUserStateService,
  ) {
    super();

    controlValueStream<ISAType>(this.isaTypeControl).pipe(takeUntil(this.destroy$)).subscribe(isaType => {
      this.compileService.setISAType(isaType);
    });
  }

  public openSettings(): void {
    this.dialog.open(SettingsDialogComponent, {
      width: '800px',
    });
  }
}
