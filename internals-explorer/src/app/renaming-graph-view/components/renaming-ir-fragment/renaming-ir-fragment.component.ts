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
import { RenamingStep } from '../../models/renaming-step.model';

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
        return defReplaced;
      }

      const { bb, i, step, succBB } = currentStep;

      const actualBB = [RenamingStep.EACH_SUCC_PHI, RenamingStep.SUCC_PHI_REPLACE_USE].includes(step) ? succBB : bb;

      if (typeof actualBB !== 'number' || typeof i !== 'number' || actualBB !== this.nodeId) {
        return defReplaced;
      }

      const [, fragmentIndex] = getVariableTextAndIndex(cfg, i === -1, i, actualBB, variable);

      return  fragmentIndex === this.i
        ? `<span class="highlighted-fragment">${defReplaced}</span>`
        : defReplaced;
    }),
    distinctUntilChanged(),
    map(html => this.sanitizer.bypassSecurityTrustHtml(html))
  );

  constructor(
    private readonly replaceNodeContentsHook: ReplaceNodeContentsHook,
    private readonly renamingStateService: RenamingStateService,
    private readonly algorithmStepService: AlgorithmStepService,
    private readonly sanitizer: DomSanitizer,
  ) {
  }
}
