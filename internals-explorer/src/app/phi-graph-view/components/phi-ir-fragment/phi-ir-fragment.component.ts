import {
  ChangeDetectionStrategy,
  Component,
  HostBinding,
  Input,
  OnInit,
  ValueProvider,
  ViewEncapsulation,
} from '@angular/core';
import { FRAGMENT_COMPONENT, FragmentComponent, FragmentSource } from '@cki-graph-view/models/fragment-component.model';
import { PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { combineLatest, distinctUntilChanged, map, Observable, ReplaySubject, startWith } from 'rxjs';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { AlgorithmPhase, AlgorithmStepService } from '../../../algorithm-stepper/services/algorithm-step.service';
import { replaceVarInText } from '@cki-graph-view/utils';
import { slak } from '@ckompiler/ckompiler';
import PhiInstruction = slak.ckompiler.analysis.PhiInstruction;

@Component({
  selector: 'cki-phi-ir-fragment',
  templateUrl: './phi-ir-fragment.component.html',
  styleUrls: ['./phi-ir-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.ShadowDom,
})
export class PhiIrFragmentComponent implements FragmentComponent, OnInit {
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
  public instr?: FragmentSource;

  @Input()
  public set text(value: string) {
    this.textSubject.next(value);
  }

  private isPhi!: boolean;

  private readonly textSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly replacedText$: Observable<SafeHtml> = combineLatest([
    this.textSubject,
    this.phiInsertionStateService.varState.targetVariable$.pipe(
      startWith(null),
      distinctUntilChanged(),
    ),
    this.algorithmStepService.phase$.pipe(
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

      const [replaced, containsVariable] = replaceVarInText(variable, text);

      const withPhiClass = containsVariable ? 'highlight-active' : 'highlight-disabled';
      const phiReplaced = this.isPhi ? `<span class="${withPhiClass}">${replaced}</span>` : replaced;

      const shouldHide = phase !== AlgorithmPhase.PREPARING && this.isPhi && (!containsVariable || !isInF);
      if (this.isFragmentHidden !== shouldHide) {
        this.isFragmentHidden = shouldHide;
        this.phiInsertionStateService.triggerReLayout(this.nodeId);
      }

      return this.sanitizer.bypassSecurityTrustHtml(phiReplaced);
    }),
  );

  constructor(
    private readonly replaceNodeContentsHook: ReplaceNodeContentsHook,
    private readonly phiInsertionStateService: PhiInsertionStateService,
    private readonly algorithmStepService: AlgorithmStepService,
    private readonly sanitizer: DomSanitizer,
  ) {
  }

  public ngOnInit(): void {
    this.isPhi = this.instr instanceof PhiInstruction;
  }
}
