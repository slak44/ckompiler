import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Observable } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { CompileService } from '@cki-graph-view/services/compile.service';
import Diagnostic = slak.ckompiler.Diagnostic;
import DiagnosticsStats = slak.ckompiler.DiagnosticsStats;

@Component({
  selector: 'cki-diagnostic-list',
  templateUrl: './diagnostic-list.component.html',
  styleUrls: ['./diagnostic-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DiagnosticListComponent {
  public readonly allDiagnostics$: Observable<Diagnostic[]> = this.compileService.allDiagnostics$;
  public readonly diagnosticStats$: Observable<DiagnosticsStats> = this.compileService.diagnosticStats$;

  constructor(
    private readonly compileService: CompileService,
    private readonly sanitizer: DomSanitizer,
  ) {
  }

  public sanitizeDiagnostic(diagnostic: Diagnostic): SafeHtml {
    // FIXME
    return this.sanitizer.bypassSecurityTrustHtml(diagnostic.printable);
  }
}
