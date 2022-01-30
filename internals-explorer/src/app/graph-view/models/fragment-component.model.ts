import { InjectionToken, Type } from '@angular/core';

export const GENERIC_FRAGMENT_HOST = 'cki-generic-fragment-component';

export const FRAGMENT_COMPONENT: InjectionToken<Type<FragmentComponent>> = new InjectionToken(GENERIC_FRAGMENT_HOST);

export interface FragmentComponent {
  color: string;
  text: string;
  printingType: string;
}
