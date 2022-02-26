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
import { combineLatest, distinctUntilChanged, map, Observable, ReplaySubject, startWith } from 'rxjs';
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
    this.phiInsertionStateService.targetVariable$.pipe(
      startWith(null),
      distinctUntilChanged(),
    ),
    this.phiInsertionStateService.phiInsertionPhase$.pipe(
      distinctUntilChanged(),
    ),
    this.phiInsertionStateService.currentStepState$.pipe(
      startWith(null),
      map(maybeState => maybeState?.f.includes(this.nodeId) ?? false),
      distinctUntilChanged(),
    ),
    this.replaceNodeContentsHook.rerender$.pipe(startWith(null)),
  ]).pipe(
    map(([text, variable, phase, isInF]): SafeHtml => {
      if (!variable) {
        return text;
      }

      const replacePattern = '<span class="highlight-variable">$1</span>';
      const regexp = new RegExp(`(${variable.toString()})`, 'g');
      let containsVariable = false;
      const replaced = text.replace(regexp, (variable) => {
        containsVariable = true;

        return replacePattern.replace('$1', variable);
      });
      const isPhi = replaced.includes('φ');
      const withPhiClass = containsVariable ? 'highlight-active' : 'highlight-disabled';
      const phiReplaced = isPhi ? `<span class="${withPhiClass}">${replaced}</span>` : replaced;

      const shouldHide = phase !== PhiInsertionPhase.CONFIGURE && isPhi && (!containsVariable || !isInF);
      if (this.isFragmentHidden !== shouldHide) {
        this.isFragmentHidden = shouldHide;
        this.phiInsertionStateService.triggerReLayout(this.nodeId);
      }

      return this.sanitizer.bypassSecurityTrustHtml(phiReplaced);
    }),
  );

  constructor(
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
    private phiInsertionStateService: PhiInsertionStateService,
    private sanitizer: DomSanitizer,
  ) {
  }
}
