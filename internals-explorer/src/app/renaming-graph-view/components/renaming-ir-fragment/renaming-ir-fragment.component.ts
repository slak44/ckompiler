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
import { replaceVarInText } from '@cki-graph-view/utils';

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
  public set text(value: string) {
    this.textSubject.next(value);
  }

  private readonly textSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly text$: Observable<SafeHtml> = combineLatest([
    this.textSubject,
    this.renamingStateService.varState.targetVariable$.pipe(
      startWith(null),
      distinctUntilChanged(),
    ),
    this.algorithmStepService.phase$.pipe(
      distinctUntilChanged(),
    ),
    this.replaceNodeContentsHook.rerender$.pipe(startWith(null)),
  ]).pipe(
    map(([text, variable]) => {
      if (!variable) {
        return text;
      }

      const [replaced] = replaceVarInText(variable, text);

      return this.sanitizer.bypassSecurityTrustHtml(replaced);
    }),
  );

  constructor(
    private replaceNodeContentsHook: ReplaceNodeContentsHook,
    private renamingStateService: RenamingStateService,
    private algorithmStepService: AlgorithmStepService,
    private sanitizer: DomSanitizer,
  ) {
  }
}
