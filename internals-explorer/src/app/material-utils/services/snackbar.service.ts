import { Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

@Injectable({
  providedIn: 'root',
})
export class SnackbarService {
  constructor(
    private readonly snackBar: MatSnackBar,
  ) {
  }

  public showLongSnackWithDismiss(message: string): void {
    const ref = this.snackBar.open(message, 'DISMISS', {
      duration: 30_000,
    });
    ref.onAction().subscribe(() => ref.dismiss());
  }
}
