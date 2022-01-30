import {
  ChangeDetectionStrategy,
  Component,
  HostBinding,
  Input,
  ValueProvider,
  ViewEncapsulation,
} from '@angular/core';
import { FRAGMENT_COMPONENT, FragmentComponent } from '@cki-graph-view/models/fragment-component.model';
import { PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { combineLatest, map, Observable, ReplaySubject, startWith } from 'rxjs';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

@Component({
  selector: 'cki-phi-ir-fragment',
  templateUrl: './phi-ir-fragment.component.html',
  styleUrls: ['./phi-ir-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.ShadowDom,
})
export class PhiIrFragmentComponent implements FragmentComponent {
  public static provider: ValueProvider = {
    provide: FRAGMENT_COMPONENT,
    useValue: PhiIrFragmentComponent,
  };

  @Input()
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public set text(value: string) {
    this.textSubject.next(value);
  }

  private readonly textSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly replacedText$: Observable<SafeHtml> = combineLatest([
    this.textSubject,
    this.phiInsertionStateService.targetVariable$.pipe(startWith(null)),
  ]).pipe(
    map(([text, variable]): SafeHtml => {
      if (!variable) {
        return text;
      } else {
        const replacePattern = '<span class="highlight-variable">$1</span>';
        const regexp = new RegExp(`(${variable.toString()})`, 'g');
        const replaced = text.replace(regexp, replacePattern);

        return this.sanitizer.bypassSecurityTrustHtml(replaced);
      }
    }),
  );

  constructor(
    private phiInsertionStateService: PhiInsertionStateService,
    private sanitizer: DomSanitizer,
  ) {
  }
}
