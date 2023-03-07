import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Location } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { map, Observable, tap } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { CompileService } from '@cki-graph-view/services/compile.service';
import { MatDialog } from '@angular/material/dialog';
import { SettingsDialogComponent } from '../../../settings/components/settings-dialog/settings-dialog.component';
import { FormControl } from '@angular/forms';
import { controlValueStream } from '@cki-utils/form-control-observable';
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;
import ISAType = slak.ckompiler.backend.ISAType;

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
export class LiveCompileComponent {
  public readonly SOURCE_CODE_PATH = SOURCE_CODE_PATH;
  public readonly DIAGNOSTICS_PATH = DIAGNOSTICS_PATH;
  public readonly CFG_PATH = CFG_PATH;
  public readonly PHI_PATH = PHI_PATH;
  public readonly RENAME_PATH = RENAME_PATH;

  public readonly isaTypeValues = ISAType.values();

  public readonly isaTypeControl: FormControl = new FormControl(ISAType.X64);

  public readonly isaType$: Observable<ISAType> = controlValueStream<ISAType>(this.isaTypeControl);

  public readonly initialSource$: Observable<string> =
    this.httpClient.get(this.location.prepareExternalUrl('/assets/samples/default.c'), { responseType: 'text' });

  public readonly diagnosticStats$: Observable<DiagnosticsStats> = this.compileService.diagnosticStats$;

  public readonly hasErrors$: Observable<boolean> = this.compileService.diagnosticStats$.pipe(
    map(stats => stats.errors > 0),
    tap(hasErrors => {
      if (hasErrors && this.selectedTabIndex > 1) {
        this.selectedTabIndex = 1;
      }
    }),
  );

  public selectedTabIndex: number = 0;

  constructor(
    private readonly httpClient: HttpClient,
    private readonly compileService: CompileService,
    private readonly location: Location,
    private readonly dialog: MatDialog
  ) {
  }

  public openSettings(): void {
    this.dialog.open(SettingsDialogComponent, {
      width: '800px',
    });
  }
}
