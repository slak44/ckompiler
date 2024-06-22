import { ChangeDetectionStrategy, Component, OnInit, TemplateRef, ViewChild } from '@angular/core';
import { filter, Observable, takeUntil } from 'rxjs';
import { ViewStateListing, ViewStateMetadata } from '../../../settings/models/view-state.model';
import { ViewStateService } from '../../../settings/services/view-state.service';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ShareViewstateDialogComponent } from '../share-viewstate-dialog/share-viewstate-dialog.component';
import { recentPublicShareLinks } from '@cki-settings';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { BroadcastViewStateService } from '../../../broadcast/services/broadcast-view-state.service';
import { BroadcastId, BroadcastService } from '../../../broadcast/services/broadcast.service';
import { CommonModule } from '@angular/common';
import { MatRippleModule } from '@angular/material/core';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatBadgeModule } from '@angular/material/badge';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { environment } from '../../../../environments/environment';
import { BROADCAST_ROUTE, PUBLIC_SHARE_ROUTE } from '@cki-utils/routes';
import { Router } from '@angular/router';
import { SnackbarService } from '../../../material-utils/services/snackbar.service';

@Component({
  selector: 'cki-viewstate-list',
  templateUrl: './viewstate-list.component.html',
  styleUrls: ['./viewstate-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatRippleModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatTooltipModule,
    MatBadgeModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
  ],
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
    private readonly broadcastService: BroadcastService,
    private readonly router: Router,
    private readonly snackbarService: SnackbarService,
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
    this.viewStateService.fetchAndRestoreState(stateId).pipe(takeUntil(this.destroy$)).subscribe({
      next: (viewState) => {
        this.router.navigate([viewState.activeRoute]).catch(console.error);
      },
      error: error => {
        console.error(error);
        this.snackbarService.showLongSnackWithDismiss('Failed to load view state.');
      }
    });
  }

  public restorePublicState(stateId: string): void {
    this.router.navigate([PUBLIC_SHARE_ROUTE, stateId]).catch(console.error);
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

  public copyBroadcastLink(broadcastId: BroadcastId): void {
    navigator.clipboard.writeText(`${environment.rootUrl}/${BROADCAST_ROUTE}/${broadcastId}`)
      .catch(error => console.error(error));
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
