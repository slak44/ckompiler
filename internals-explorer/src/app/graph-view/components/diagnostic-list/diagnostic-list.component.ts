import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CompileService } from '../../services/compile.service';
import { Observable } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
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
    private compileService: CompileService,
    private sanitizer: DomSanitizer,
  ) {
  }

  public sanitizeDiagnostic(diagnostic: Diagnostic): SafeHtml {
    // FIXME
    return this.sanitizer.bypassSecurityTrustHtml(diagnostic.printable);
  }
}