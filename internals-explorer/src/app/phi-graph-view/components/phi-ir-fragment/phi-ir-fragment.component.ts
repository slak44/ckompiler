import {
  ChangeDetectionStrategy,
  Component,
  HostBinding,
  Input,
  ValueProvider,
  ViewEncapsulation,
} from '@angular/core';
import { FRAGMENT_COMPONENT, FragmentComponent } from '@cki-graph-view/models/fragment-component.model';
import { PhiInsertionPhase, PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { combineLatest, map, Observable, ReplaySubject, startWith } from 'rxjs';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';

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

  @HostBinding('class.hidden-fragment')
  private isFragmentHidden: boolean = false;

  @Input()
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public nodeId!: number;

  @Input()
  public set text(value: string) {
    this.textSubject.next(value);
  }

  private readonly textSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly replacedText$: Observable<SafeHtml> = combineLatest([
    this.textSubject,
    this.phiInsertionStateService.targetVariable$.pipe(startWith(null)),
    this.phiInsertionStateService.phiInsertionPhase$,
    this.replaceNodeContentsHook.rerender$.pipe(startWith(null)),
  ]).pipe(
    map(([text, variable, state]): SafeHtml => {
      if (!variable) {
        return text;
      } else {
        const replacePattern = '<span class="highlight-variable">$1</span>';
        const regexp = new RegExp(`(${variable.toString()})`, 'g');
        let didReplace = false;
        const replaced = text.replace(regexp, (variable) => {
          didReplace = true;

          return replacePattern.replace('$1', variable);
        });
        const isPhi = replaced.includes('φ');
        const withPhiClass = didReplace ? 'highlight-active' : 'highlight-disabled';
        const phiReplaced = isPhi ? `<span class="${withPhiClass}">${replaced}</span>` : replaced;

        const shouldHide = state !== PhiInsertionPhase.CONFIGURE && isPhi;
        if (this.isFragmentHidden !== shouldHide) {
          this.isFragmentHidden = shouldHide;
          this.phiInsertionStateService.triggerReLayout(this.nodeId);
        }

        return this.sanitizer.bypassSecurityTrustHtml(phiReplaced);
      }
    }),
  );

  constructor(
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
    private phiInsertionStateService: PhiInsertionStateService,
    private sanitizer: DomSanitizer,
  ) {
  }
}
