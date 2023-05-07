import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Location } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { combineLatest, map, Observable, takeUntil, tap } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { MatDialog } from '@angular/material/dialog';
import { SettingsDialogComponent } from '../../../settings/components/settings-dialog/settings-dialog.component';
import { FormControl } from '@angular/forms';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { isaType } from '@cki-settings';
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;
import ISAType = slak.ckompiler.backend.ISAType;
import { AuthService } from '@auth0/auth0-angular';
import { ViewStateService } from '../../../settings/services/view-state.service';

export const SOURCE_CODE_PATH = 'source-code';
export const DIAGNOSTICS_PATH = 'diagnostics';
export const CFG_PATH = 'cfg';
export const PHI_PATH = 'phi';
export const RENAME_PATH = 'rename';

@Component({
  selector: 'cki-live-compile',
  templateUrl: './live-compile.component.html',
  styleUrls: ['./live-compile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveCompileComponent extends SubscriptionDestroy {
  public readonly SOURCE_CODE_PATH = SOURCE_CODE_PATH;
  public readonly DIAGNOSTICS_PATH = DIAGNOSTICS_PATH;
  public readonly CFG_PATH = CFG_PATH;
  public readonly PHI_PATH = PHI_PATH;
  public readonly RENAME_PATH = RENAME_PATH;

  public readonly isaTypeValues = ISAType.values();

  public readonly isaTypeControl: FormControl = isaType.formControl;

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
      if (hasErrors && this.selectedTabIndex > 1) {
        this.selectedTabIndex = 1;
      }
    }),
  );

  public selectedTabIndex: number = 0;

  public readonly user$ = this.authService.user$;

  public readonly stateLock$: Observable<boolean> = this.viewStateService.stateLock$;

  constructor(
    private readonly httpClient: HttpClient,
    private readonly compileService: CompileService,
    private readonly location: Location,
    private readonly dialog: MatDialog,
    private readonly authService: AuthService,
    private readonly viewStateService: ViewStateService,
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
