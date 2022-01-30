import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Location } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { map, Observable, tap } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { CompileService } from '@cki-graph-view/services/compile.service';
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;

export const SOURCE_CODE_PATH = 'source-code';
export const DIAGNOSTICS_PATH = 'diagnostics';
export const CFG_PATH = 'cfg';
export const PHI_PATH = 'phi';

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

  public readonly initialSource$: Observable<string> =
    this.httpClient.get(this.location.prepareExternalUrl('/assets/default.c'), { responseType: 'text' });

  public readonly diagnosticStats$: Observable<DiagnosticsStats> = this.compileService.diagnosticStats$;

  public readonly hasErrors$: Observable<boolean> = this.compileService.diagnosticStats$.pipe(
    map(stats => stats.errors > 0),
    tap(hasErrors => {
      if (hasErrors && this.selectedTabIndex > 1) {
        this.selectedTabIndex = 1;
      }
    })
  );

  public selectedTabIndex: number = 0;

  constructor(
    private httpClient: HttpClient,
    private compileService: CompileService,
    private location: Location,
  ) {
  }
}
