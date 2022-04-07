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
import { combineLatest, distinctUntilChanged, map, Observable, startWith } from 'rxjs';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { RenamingStateService } from '../../services/renaming-state.service';
import { AlgorithmStepService } from '../../../algorithm-stepper/services/algorithm-step.service';
import { RenamingStep } from '../../models/renaming-step.model';
import { slak } from '@ckompiler/ckompiler';
import PhiInstruction = slak.ckompiler.analysis.PhiInstruction;
import StoreMemory = slak.ckompiler.analysis.StoreMemory;
import Variable = slak.ckompiler.analysis.Variable;
import getRenameText = slak.ckompiler.analysis.external.getRenameText;
import { getNodeById, replaceVarInText } from '@cki-graph-view/utils';
import getPhiRenameText = slak.ckompiler.analysis.external.getPhiRenameText;

@Component({
  selector: 'cki-renaming-ir-fragment',
  templateUrl: './renaming-ir-fragment.component.html',
  styleUrls: ['./renaming-ir-fragment.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.ShadowDom,
})
export class RenamingIrFragmentComponent implements FragmentComponent, OnInit {
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
  public instr?: FragmentSource;

  private resultIdentityId?: number;

  public readonly text$: Observable<SafeHtml> = combineLatest([
    this.renamingStateService.varState.targetVariable$.pipe(
      startWith(null),
      distinctUntilChanged(),
    ),
    this.renamingStateService.currentStepState$.pipe(
      startWith(null),
    ),
    this.algorithmStepService.currentStep$.pipe(
      startWith(0),
    ),
    this.renamingStateService.renameReplacements$.pipe(
      startWith(null),
    ),
    this.renamingStateService.compilationInstance.cfg$,
    this.algorithmStepService.phase$.pipe(
      distinctUntilChanged(),
    ),
    this.replaceNodeContentsHook.rerender$.pipe(
      startWith(null),
    ),
  ]).pipe(
    map(([variable, currentStep, currentStepIdx, renameReplacements, cfg]) => {
      let text: string;

      const node = getNodeById(cfg, this.nodeId);

      if (!this.instr) {
        text = `BB${this.nodeId}:`;
      } else if (!variable || !renameReplacements) {
        text = this.instr.toString();
      } else if (this.instr instanceof PhiInstruction) {
        text = getPhiRenameText(this.instr, node, this.i, variable, currentStepIdx, renameReplacements);
      } else {
        text = getRenameText(this.instr, node, this.i, variable, currentStepIdx, renameReplacements);
      }

      if (!variable) {
        return text;
      }

      const [replaced] = replaceVarInText(variable, text);

      const defReplaced = this.resultIdentityId === variable.identityId
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

      return this.i === i
        ? `<span class="highlighted-fragment">${defReplaced}</span>`
        : defReplaced;
    }),
    distinctUntilChanged(),
    map(html => this.sanitizer.bypassSecurityTrustHtml(html)),
  );

  constructor(
    private readonly replaceNodeContentsHook: ReplaceNodeContentsHook,
    private readonly renamingStateService: RenamingStateService,
    private readonly algorithmStepService: AlgorithmStepService,
    private readonly sanitizer: DomSanitizer,
  ) {
  }

  public ngOnInit(): void {
    if (this.instr instanceof PhiInstruction) {
      this.resultIdentityId = this.instr.variable.identityId;
    } else if (this.instr && !(this.instr instanceof StoreMemory) && this.instr.result instanceof Variable) {
      this.resultIdentityId = this.instr.result.identityId;
    }
  }
}
