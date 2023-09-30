import { ChangeDetectionStrategy, Component, OnInit, TemplateRef, ViewChild } from '@angular/core';
import { filter, Observable, takeUntil } from 'rxjs';
import { ViewStateListing, ViewStateMetadata } from '../../../settings/models/view-state.model';
import { ViewStateService } from '../../../settings/services/view-state.service';
import { MatLegacyDialog as MatDialog } from '@angular/material/legacy-dialog';
import { ShareViewstateDialogComponent } from '../share-viewstate-dialog/share-viewstate-dialog.component';
import { recentPublicShareLinks } from '@cki-settings';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { BroadcastViewStateService } from '../../../broadcast/services/broadcast-view-state.service';
import { BroadcastId, BroadcastService } from '../../../broadcast/services/broadcast.service';

@Component({
  selector: 'cki-viewstate-list',
  templateUrl: './viewstate-list.component.html',
  styleUrls: ['./viewstate-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ViewstateListComponent extends SubscriptionDestroy implements OnInit {
  @ViewChild('newStateConfirm') private readonly newStateConfirm!: TemplateRef<unknown>;
  @ViewChild('deleteConfirm') private readonly deleteConfirm!: TemplateRef<unknown>;

  public readonly viewStates$: Observable<ViewStateListing[]> = this.viewStateService.viewStates$;
  public readonly recentPublicLinks$: Observable<ViewStateMetadata[]> = recentPublicShareLinks.value$;
  public readonly broadcastPublishId$: Observable<BroadcastId | undefined> = this.broadcastService.broadcastPublishId$;
  public readonly broadcastSubscribeId$: Observable<BroadcastId | undefined> =
    this.broadcastService.broadcastSubscribeId$;

  constructor(
    private readonly viewStateService: ViewStateService,
    private readonly dialog: MatDialog,
    private readonly broadcastViewStateService: BroadcastViewStateService,
    private readonly broadcastService: BroadcastService
  ) {
    super();
  }

  public ngOnInit(): void {
    this.viewStateService.refreshViewStates();
  }

  public saveCurrentState(): void {
    this.dialog.open(this.newStateConfirm)
      .afterClosed()
      .pipe(
        filter((name): name is string => typeof name === 'string'),
        takeUntil(this.destroy$),
      )
      .subscribe(name => {
        this.viewStateService.saveCurrentState(name);
      });
  }

  public restoreState(stateId: string): void {
    this.viewStateService.fetchAndRestoreState(stateId).pipe(takeUntil(this.destroy$)).subscribe();
  }

  public startBroadcast(): void {
    this.broadcastViewStateService.startBroadcasting();
  }

  public closeBroadcast(broadcastId: BroadcastId): void {
    this.broadcastViewStateService.stopBroadcasting(broadcastId);
  }

  public stopWatching(): void {
    this.broadcastViewStateService.stopWatching();
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
        takeUntil(this.destroy$),
      )
      .subscribe(() => {
        this.viewStateService.deleteState(id);
      });
  }
}
