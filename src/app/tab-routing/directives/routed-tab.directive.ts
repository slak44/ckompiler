import { Directive, Input } from '@angular/core';
import { MatTab } from '@angular/material/tabs';

@Directive({
  selector: 'mat-tab[ckiRoutedTab]',
  standalone: true,
})
export class RoutedTabDirective {
  @Input('ckiRoutedTab')
  public routeName!: string;

  public svgElementRef?: SVGGElement;

  constructor(
    public matTab: MatTab,
  ) {
  }
}
