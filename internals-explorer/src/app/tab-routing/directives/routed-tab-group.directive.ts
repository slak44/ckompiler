import { AfterContentInit, ContentChildren, Directive, QueryList } from '@angular/core';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';
import { RoutedTabDirective } from './routed-tab.directive';
import { MatTabGroup } from '@angular/material/tabs';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter, ReplaySubject, takeUntil } from 'rxjs';
import { GraphMouseTrackerService } from '../../broadcast/services/graph-mouse-tracker.service';

@Directive({
  selector: 'mat-tab-group[ckiRoutedTabGroup]',
  standalone: true,
})
export class RoutedTabGroupDirective extends SubscriptionDestroy implements AfterContentInit {
  @ContentChildren(RoutedTabDirective)
  private readonly routedTabsQl!: QueryList<RoutedTabDirective>;

  private readonly navigationSubject: ReplaySubject<void> = new ReplaySubject<void>(1);

  constructor(
    private readonly matTabGroup: MatTabGroup,
    private readonly router: Router,
    private readonly activatedRoute: ActivatedRoute,
    private readonly graphMouseTrackerService: GraphMouseTrackerService,
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
        this.graphMouseTrackerService.setCurrentSVGGElement(tabDirective.svgElementRef);

        this.router.navigate([tabDirective.routeName], { relativeTo: this.activatedRoute })
          .catch(error => console.error(error));
      }
    });

    this.navigationSubject.pipe(
      takeUntil(this.destroy$),
    ).subscribe(() => {
      let child = this.router.routerState.snapshot.root;
      while (child.firstChild !== null) {
        child = child.firstChild;
      }

      const activeIndex = this.routedTabsQl.toArray()
        .findIndex((directive) => directive.routeName === child.routeConfig?.path);

      if (activeIndex !== -1) {
        this.graphMouseTrackerService.setCurrentSVGGElement(this.routedTabsQl.get(activeIndex)?.svgElementRef);

        this.matTabGroup.selectedIndex = activeIndex;
      }
    });
  }
}
