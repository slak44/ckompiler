import { Injectable } from '@angular/core';
import { BehaviorSubject, fromEvent, Observable, takeUntil } from 'rxjs';
import { MousePosition } from '../../settings/models/view-state.model';
import { SubscriptionDestroy } from '@cki-utils/subscription-destroy';

@Injectable({
  providedIn: 'root',
})
export class GraphMouseTrackerService extends SubscriptionDestroy {
  private currentSVGElement?: SVGGElement;

  /**
   * For subscribers, the position of the broadcaster mouse.
   */
  private readonly broadcasterMouseSubject: BehaviorSubject<MousePosition> = new BehaviorSubject<MousePosition>({
    x: 0,
    y: 0,
  });

  public readonly broadcasterMouse$: Observable<MousePosition> = this.broadcasterMouseSubject;

  /**
   * The position of the current user's mouse, in SVG space (aka view box coordinates).
   */
  private readonly mousePositionSubject: BehaviorSubject<MousePosition> = new BehaviorSubject<MousePosition>({
    x: 0,
    y: 0,
  });

  public readonly mousePosition$: Observable<MousePosition> = this.mousePositionSubject;

  constructor() {
    super();

    fromEvent<MouseEvent>(window, 'mousemove').pipe(
      takeUntil(this.destroy$),
    ).subscribe((event) => this.setCurrentUserMousePosition(event));
  }

  public setCurrentUserMousePosition(event: MouseEvent): void {
    this.mousePositionSubject.next(this.screenPointToSVGSpace({ x: event.clientX, y: event.clientY }));
  }

  public screenPointToSVGSpace(point: MousePosition): MousePosition {
    if (this.currentSVGElement) {
      const screenPoint = DOMPoint.fromPoint(point);
      const svgSpacePoint = screenPoint.matrixTransform(this.currentSVGElement.getScreenCTM()!.inverse());

      return {
        x: svgSpacePoint.x,
        y: svgSpacePoint.y,
      };
    } else {
      return {
        x: point.x / window.innerWidth,
        y: point.y / window.innerHeight,
      };
    }
  }

  public svgSpaceToScreenPoint(point: MousePosition): MousePosition {
    if (this.currentSVGElement) {
      const svgSpacePoint = DOMPoint.fromPoint(point);
      const screenPoint = svgSpacePoint.matrixTransform(this.currentSVGElement.getScreenCTM()!);

      return {
        x: screenPoint.x,
        y: screenPoint.y,
      };
    } else {
      return {
        x: point.x * window.innerWidth,
        y: point.y * window.innerHeight,
      };
    }
  }

  public setCurrentSVGGElement(svgGElement?: SVGGElement): void {
    this.currentSVGElement = svgGElement;
  }

  public setBroadcasterMousePosition(pos: MousePosition): void {
    this.broadcasterMouseSubject.next(pos);
  }
}
