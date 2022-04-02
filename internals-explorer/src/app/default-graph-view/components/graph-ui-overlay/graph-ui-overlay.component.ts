import { AfterViewInit, ChangeDetectionStrategy, Component, Input, ViewChild } from '@angular/core';
import { Observable, ReplaySubject, takeUntil } from 'rxjs';
import { GraphOptionsComponent } from '../graph-options/graph-options.component';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { CompilationInstance } from '@cki-graph-view/compilation-instance';

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

  private readonly printingTypeSubject: ReplaySubject<string> = new ReplaySubject(1);

  public readonly printingType$: Observable<string> = this.printingTypeSubject;

  constructor() {
    super();
  }

  public ngAfterViewInit(): void {
    this.graphOptions.printingValue$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(value => {
      this.printingTypeSubject.next(value);
    });
  }
}
