import { ChangeDetectionStrategy, Component, OnInit, TemplateRef, ViewChild } from '@angular/core';
import { filter, Observable } from 'rxjs';
import { ViewStateListing } from '../../../settings/models/view-state.model';
import { ViewStateService } from '../../../settings/services/view-state.service';
import { MatDialog } from '@angular/material/dialog';

@Component({
  selector: 'cki-viewstate-list',
  templateUrl: './viewstate-list.component.html',
  styleUrls: ['./viewstate-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ViewstateListComponent implements OnInit {
  @ViewChild('newStateConfirm') private readonly newStateConfirm!: TemplateRef<unknown>;
  @ViewChild('deleteConfirm') private readonly deleteConfirm!: TemplateRef<unknown>;

  public readonly viewStates$: Observable<ViewStateListing[]> = this.viewStateService.viewStates$;

  constructor(
    private readonly viewStateService: ViewStateService,
    private readonly dialog: MatDialog,
  ) {
  }

  public ngOnInit(): void {
    this.viewStateService.refreshViewStates();
  }

  public saveCurrentState(): void {
    this.dialog.open(this.newStateConfirm)
      .afterClosed()
      .pipe(
        filter((name): name is string => typeof name === 'string'),
      )
      .subscribe(name => {
        this.viewStateService.saveCurrentState(name);
      });
  }

  public restoreState(viewState: ViewStateListing): void {
    this.viewStateService.fetchAndRestoreState(viewState.id!);
  }

  public deleteState(id: string): void {
    this.dialog.open(this.deleteConfirm)
      .afterClosed()
      .pipe(
        filter((shouldDelete) => shouldDelete === true),
      )
      .subscribe(() => {
        this.viewStateService.deleteState(id);
      });
  }
}
