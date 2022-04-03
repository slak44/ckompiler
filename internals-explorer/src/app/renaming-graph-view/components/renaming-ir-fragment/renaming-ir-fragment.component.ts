import {
  ChangeDetectionStrategy,
  Component,
  HostBinding,
  Input,
  ValueProvider,
  ViewEncapsulation,
} from '@angular/core';
import { FRAGMENT_COMPONENT, FragmentComponent } from '@cki-graph-view/models/fragment-component.model';
import { combineLatest, distinctUntilChanged, map, Observable, ReplaySubject, startWith } from 'rxjs';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { RenamingStateService } from '../../services/renaming-state.service';
import { AlgorithmStepService } from '../../../algorithm-stepper/services/algorithm-step.service';
import { getVariableTextAndIndex, replaceVarInText } from '@cki-graph-view/utils';

@Component({
  selector: 'cki-renaming-ir-fragment',
  templateUrl: './renaming-ir-fragment.component.html',
  styleUrls: ['./renaming-ir-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.ShadowDom,
})
export class RenamingIrFragmentComponent implements FragmentComponent {
  public static provider: ValueProvider = {
    provide: FRAGMENT_COMPONENT,
    useValue: RenamingIrFragmentComponent,
  };

  @Input()
  @HostBinding('style.color')
  public color: string = '';

  @Input()
  public nodeId!: number;

  @Input()
  public i!: number;

  @Input()
  public set text(value: string) {
    this.textSubject.next(value);
  }

  private readonly textSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly text$: Observable<SafeHtml> = combineLatest([
    this.textSubject,
    this.renamingStateService.compilationInstance.cfg$,
    this.renamingStateService.varState.targetVariable$.pipe(
      startWith(null),
      distinctUntilChanged(),
    ),
    this.renamingStateService.currentStepState$.pipe(
      startWith(null),
      distinctUntilChanged((a, b) => a?.bb === b?.bb && a?.i === b?.i)
    ),
    this.algorithmStepService.phase$.pipe(
      distinctUntilChanged(),
    ),
    this.replaceNodeContentsHook.rerender$.pipe(
      startWith(null)
    ),
  ]).pipe(
    map(([text, cfg, variable, currentStep]) => {
      if (!variable) {
        return text;
      }

      const [replaced, containsVariable] = replaceVarInText(variable, text);

      const isPhi = text.includes('φ');
      const isMoveInstr = text.startsWith('move ' + variable.tid.toString());

      const defReplaced = containsVariable && (isPhi || isMoveInstr)
        ? `<span class="variable-definition">${replaced}</span>`
        : replaced;

      if (!currentStep) {
        return this.sanitizer.bypassSecurityTrustHtml(defReplaced);
      }

      const { bb, i } = currentStep;

      if (typeof bb !== 'number' || typeof i !== 'number') {
        return this.sanitizer.bypassSecurityTrustHtml(defReplaced);
      }

      const [, fragmentIndex] = getVariableTextAndIndex(cfg, isPhi, i, bb, variable);

      const bgHighlight = bb === this.nodeId && fragmentIndex === this.i
        ? `<span class="highlighted-fragment">${defReplaced}</span>`
        : defReplaced;

      return this.sanitizer.bypassSecurityTrustHtml(bgHighlight);
    }),
  );

  constructor(
    private readonly replaceNodeContentsHook: ReplaceNodeContentsHook,
    private readonly renamingStateService: RenamingStateService,
    private readonly algorithmStepService: AlgorithmStepService,
    private readonly sanitizer: DomSanitizer,
  ) {
  }
}
