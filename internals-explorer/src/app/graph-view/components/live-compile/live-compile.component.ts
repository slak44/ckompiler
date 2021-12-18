import { ChangeDetectionStrategy, Component } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, Observable, tap } from 'rxjs';
import { CompileService } from '../../services/compile.service';
import { slak } from '@ckompiler/ckompiler';
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;

@Component({
  selector: 'cki-live-compile',
  templateUrl: './live-compile.component.html',
  styleUrls: ['./live-compile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveCompileComponent {
  public readonly initialSource$: Observable<string> =
    this.httpClient.get('/assets/default.c', { responseType: 'text' });

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
  ) {
  }
}
