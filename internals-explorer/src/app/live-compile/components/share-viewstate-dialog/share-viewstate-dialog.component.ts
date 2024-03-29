import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Inject } from '@angular/core';
import { ViewStateListing } from '../../../settings/models/view-state.model';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { ViewStateService } from '../../../settings/services/view-state.service';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { BehaviorSubject, finalize, Observable, takeUntil } from 'rxjs';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { environment } from '../../../../environments/environment';
import { PUBLIC_SHARE_ROUTE } from '@cki-utils/routes';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatRippleModule } from '@angular/material/core';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'cki-share-viewstate-dialog',
  templateUrl: './share-viewstate-dialog.component.html',
  styleUrls: ['./share-viewstate-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatIconModule,
    MatRippleModule,
    MatSlideToggleModule,
    MatButtonModule,
  ],
})
export class ShareViewstateDialogComponent extends SubscriptionDestroy {
  private readonly loadingSubject: BehaviorSubject<boolean> = new BehaviorSubject<boolean>(false);
  public readonly loading$: Observable<boolean> = this.loadingSubject;

  public readonly publicLink: string = `${environment.rootUrl}/${PUBLIC_SHARE_ROUTE}/${this.viewStateListing.id}`;

  public wasCopied: boolean = false;

  constructor(
    @Inject(MAT_DIALOG_DATA) public readonly viewStateListing: ViewStateListing,
    private readonly viewStateService: ViewStateService,
    private readonly changeDetectorRef: ChangeDetectorRef,
  ) {
    super();
  }

  public configurePublicShare(change: MatSlideToggleChange): void {
    this.loadingSubject.next(true);

    this.viewStateService.configurePublicShare(this.viewStateListing.id!, change.checked).pipe(
      finalize(() => this.loadingSubject.next(false)),
      takeUntil(this.destroy$),
    ).subscribe(() => {
      this.viewStateListing.publicShareEnabled = change.checked;
    });
  }

  public copyToClipboard(): void {
    navigator.clipboard.writeText(this.publicLink)
      .then(() => {
        this.wasCopied = true;
        this.changeDetectorRef.markForCheck();
      })
      .catch(error => console.error(error));
  }
}
