import { Component, OnInit, ChangeDetectionStrategy, Input, Output, EventEmitter } from '@angular/core';
import { FormControl } from '@angular/forms';
import { filter, Observable, takeUntil } from 'rxjs';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { slak } from '@ckompiler/ckompiler';
import Variable = slak.ckompiler.analysis.Variable;
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';

@Component({
  selector: 'cki-select-variable',
  templateUrl: './select-variable.component.html',
  styleUrls: ['./select-variable.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelectVariableComponent extends SubscriptionDestroy implements OnInit {
  @Input()
  public variables$!: Observable<Variable[]>;

  @Output()
  public readonly identityIdChange: EventEmitter<number> = new EventEmitter<number>();

  @Output()
  public readonly startClick: EventEmitter<void> = new EventEmitter<void>();

  public readonly variableControl: FormControl = new FormControl(null);

  public readonly variableId$: Observable<number> = controlValueStream<number | null>(this.variableControl).pipe(
    filter((identityId): identityId is number => typeof identityId === 'number'),
  );

  constructor() {
    super();
  }

  public ngOnInit(): void {
    this.variableId$.pipe(
      takeUntil(this.destroy$),
    ).subscribe(identityId => {
      this.identityIdChange.emit(identityId);
    });
  }
}
