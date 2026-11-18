import { Observable, startWith } from 'rxjs';
import { AbstractControl } from '@angular/forms';

export function controlValueStream<T>(formControl: AbstractControl): Observable<T> {
  return (formControl.valueChanges as Observable<T>).pipe(
    startWith(formControl.value as T)
  );
}
