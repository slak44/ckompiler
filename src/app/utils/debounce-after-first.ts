import { debounce, MonoTypeOperatorFunction, of, timer } from 'rxjs';

export function debounceAfterFirst<T>(time: number): MonoTypeOperatorFunction<T> {
  let wasFirst = true;

  return debounce<T>(() => {
    const time$ = wasFirst ? of(0) : timer(time);
    wasFirst = false;
    return time$;
  });
}
