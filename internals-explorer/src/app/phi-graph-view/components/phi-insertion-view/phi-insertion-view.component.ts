import { ChangeDetectionStrategy, Component, HostListener } from '@angular/core';
import { filter, map, Observable, of, takeUntil } from 'rxjs';
import { slak } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { PhiIrFragmentComponent } from '../phi-ir-fragment/phi-ir-fragment.component';
import { ReplaceNodeContentsHook } from '@cki-graph-view/graph-view-hooks/replace-node-contents';
import { GraphViewHook } from '@cki-graph-view/models/graph-view-hook.model';
import { removeHoverTitles } from '@cki-graph-view/graph-view-hooks/remove-hover-titles';
import { DisableDblClick } from '@cki-graph-view/graph-view-hooks/disable-dblclick';
import { PhiInsertionState, PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import Variable = slak.ckompiler.analysis.Variable;
import JSCompileResult = slak.ckompiler.JSCompileResult;

@Component({
  selector: 'cki-phi-insertion-view',
  templateUrl: './phi-insertion-view.component.html',
  styleUrls: ['./phi-insertion-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [PhiIrFragmentComponent.provider, PhiInsertionStateService, ReplaceNodeContentsHook],
})
export class PhiInsertionViewComponent extends SubscriptionDestroy {
  public readonly printingType$: Observable<string> = of('IR_TO_STRING');

  public readonly hooks: GraphViewHook[] = [removeHoverTitles, new DisableDblClick(), this.replaceNodeContents];

  public readonly compileResult$: Observable<JSCompileResult> = this.phiInsertionStateService.compileResult$;
  public readonly variables$: Observable<Variable[]> = this.phiInsertionStateService.variables$;
  public readonly phiInsertionState$: Observable<PhiInsertionState> = this.phiInsertionStateService.phiInsertionState$;

  public readonly phiInsertionStates = PhiInsertionState;

  public readonly variableControl: FormControl = new FormControl(null);

  public readonly variableId$: Observable<number> = controlValueStream<number | null>(this.variableControl).pipe(
    filter((identityId): identityId is number => typeof identityId === 'number'),
  );

  public readonly worklist$: Observable<string> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.w.join(', ')),
  );

  public readonly processed$: Observable<string> = this.phiInsertionStateService.currentStepState$.pipe(
    map(state => state.f.join(', ')),
  );

  constructor(
    private replaceNodeContents: ReplaceNodeContentsHook,
    private phiInsertionStateService: PhiInsertionStateService,
  ) {
    super();

    this.variableId$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(identityId => {
      this.phiInsertionStateService.selectedVariableChanged(identityId);
    });
  }

  public start(): void {
    this.phiInsertionStateService.startInsertion();
  }

  public reset(): void {
    this.phiInsertionStateService.reset();
  }

  @HostListener('document:keydown.arrowright')
  public onKeyRight(): void {
    this.phiInsertionStateService.nextStep();
  }

  @HostListener('document:keydown.arrowleft')
  public onKeyLeft(): void {
    this.phiInsertionStateService.prevStep();
  }
}
