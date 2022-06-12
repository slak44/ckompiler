import { AfterViewInit, ChangeDetectionStrategy, Component, Input, ViewChild } from '@angular/core';
import { Observable, ReplaySubject, takeUntil } from 'rxjs';
import { GraphOptionsComponent } from '../graph-options/graph-options.component';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';
import { slak } from '@ckompiler/ckompiler';
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;

@Component({
  selector: 'cki-graph-ui-overlay',
  templateUrl: './graph-ui-overlay.component.html',
  styleUrls: ['./graph-ui-overlay.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphUiOverlayComponent extends SubscriptionDestroy implements AfterViewInit {
  @Input()
  public instance!: CompilationInstance;

  @ViewChild(GraphOptionsComponent)
  private readonly graphOptions!: GraphOptionsComponent;

  private readonly printingTypeSubject: ReplaySubject<CodePrintingMethods> = new ReplaySubject(1);

  public readonly printingType$: Observable<CodePrintingMethods> = this.printingTypeSubject;

  private readonly isSpillOnlySubject: ReplaySubject<boolean> = new ReplaySubject(1);

  public readonly isSpillOnly$: Observable<boolean> = this.isSpillOnlySubject;

  constructor() {
    super();
  }

  public ngAfterViewInit(): void {
    this.graphOptions.printingValue$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(value => {
      this.printingTypeSubject.next(value);
    });

    this.graphOptions.isSpillOnly$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(value => {
      this.isSpillOnlySubject.next(value);
    });
  }
}
