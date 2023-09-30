import { Directive, Input } from '@angular/core';
import { MatTab } from '@angular/material/tabs';

@Directive({
  selector: 'mat-tab[ckiRoutedTab]',
})
export class RoutedTabDirective {
  // eslint-disable-next-line @angular-eslint/no-input-rename
  @Input('ckiRoutedTab')
  public routeName!: string;

  constructor(
    public matTab: MatTab,
  ) {
  }
}
