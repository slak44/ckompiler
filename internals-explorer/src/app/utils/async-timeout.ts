import { filter, from, map, mergeMap, MonoTypeOperatorFunction, pipe } from 'rxjs';

export function requestAnimationFrameAsync(): Promise<DOMHighResTimeStamp> {
  return new Promise(resolve => requestAnimationFrame(resolve));
}

export function groupedDebounceByFrame(): MonoTypeOperatorFunction<number> {
  const lastFrameTime: Record<number, DOMHighResTimeStamp> = {};

  return pipe(
    mergeMap(element => from(requestAnimationFrameAsync()).pipe(
      filter(time => {
        const lastTime = lastFrameTime[element];
        lastFrameTime[element] = time;
        return time !== lastTime;
      }),
      map(() => element)
    )),
  );
}
