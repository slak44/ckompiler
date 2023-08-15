import { ChangeDetectionStrategy, Component, OnInit, TemplateRef, ViewChild } from '@angular/core';
import { filter, Observable } from 'rxjs';
import { ViewStateListing, ViewStateMetadata } from '../../../settings/models/view-state.model';
import { ViewStateService } from '../../../settings/services/view-state.service';
import { MatDialog } from '@angular/material/dialog';
import { ShareViewstateDialogComponent } from '../share-viewstate-dialog/share-viewstate-dialog.component';
import { recentPublicShareLinks } from '@cki-settings';

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
  public readonly recentPublicLinks$: Observable<ViewStateMetadata[]> = recentPublicShareLinks.value$;

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

  public restoreState(stateId: string): void {
    this.viewStateService.fetchAndRestoreState(stateId).subscribe();
  }

  public openShareDialog(viewState: ViewStateListing): void {
    this.dialog.open<ShareViewstateDialogComponent, ViewStateListing>(ShareViewstateDialogComponent, {
      data: viewState,
      maxWidth: 'min(100vw, 600px)',
      minHeight: '200px',
    });
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
