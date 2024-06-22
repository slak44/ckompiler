import { InjectionToken, Type } from '@angular/core';
import { CodePrintingMethods, IRInstruction, PhiInstruction } from '@ckompiler/ckompiler';

export const GENERIC_FRAGMENT_HOST = 'cki-generic-fragment-component';

export const FRAGMENT_COMPONENT: InjectionToken<Type<FragmentComponent>> = new InjectionToken(GENERIC_FRAGMENT_HOST);

export interface FragmentComponent {
  color: string;
  text?: string;
  nodeId?: number;
  i?: number;
  instr?: FragmentSource;
  printingType?: CodePrintingMethods;
}

export type FragmentSource = PhiInstruction | IRInstruction;
