import { filter, from, map, mergeMap, MonoTypeOperatorFunction, pipe, share } from 'rxjs';
import { NgZone } from '@angular/core';

export function requestAnimationFrameAsync(ngZone: NgZone): Promise<DOMHighResTimeStamp> {
  return ngZone.runOutsideAngular(() => new Promise(resolve => requestAnimationFrame(resolve)));
}

export function groupedDebounceByFrame(ngZone: NgZone): MonoTypeOperatorFunction<number> {
  const lastFrameTime: Record<number, DOMHighResTimeStamp> = {};

  return pipe(
    mergeMap(element => from(requestAnimationFrameAsync(ngZone)).pipe(
      filter(time => {
        const lastTime = lastFrameTime[element];
        lastFrameTime[element] = time;
        return time !== lastTime;
      }),
      map(() => element),
      share(),
    )),
  );
}
