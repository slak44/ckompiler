import { AfterContentInit, ContentChildren, Directive, QueryList } from '@angular/core';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { RoutedTabDirective } from './routed-tab.directive';
import { MatTabGroup } from '@angular/material/tabs';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter, ReplaySubject, takeUntil } from 'rxjs';

@Directive({
  selector: 'mat-tab-group[ckiRoutedTabGroup]',
})
export class RoutedTabGroupDirective extends SubscriptionDestroy implements AfterContentInit {
  @ContentChildren(RoutedTabDirective)
  private readonly routedTabsQl!: QueryList<RoutedTabDirective>;

  private readonly navigationSubject: ReplaySubject<void> = new ReplaySubject<void>(1);

  constructor(
    private matTabGroup: MatTabGroup,
    private readonly router: Router,
    private readonly activatedRoute: ActivatedRoute,
  ) {
    super();

    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      takeUntil(this.destroy$),
    ).subscribe(() => this.navigationSubject.next());
  }

  public ngAfterContentInit(): void {
    this.matTabGroup.selectedTabChange.pipe(
      takeUntil(this.destroy$),
    ).subscribe(tabChange => {
      const tabDirective = this.routedTabsQl.find(tabDirective => tabDirective.matTab === tabChange.tab);

      if (tabDirective) {
        void this.router.navigate([tabDirective.routeName], { relativeTo: this.activatedRoute });
      }
    });

    this.navigationSubject.pipe(
      takeUntil(this.destroy$)
    ).subscribe(() => {
      let child = this.router.routerState.snapshot.root;
      while (child.firstChild !== null) {
        child = child.firstChild;
      }

      const activeIndex = this.routedTabsQl.toArray()
        .findIndex((directive) => directive.routeName === child.routeConfig?.path);

      if (activeIndex !== -1) {
        this.matTabGroup.selectedIndex = activeIndex;
      }
    });
  }
}
