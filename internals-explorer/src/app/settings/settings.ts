import { distinctUntilChanged, fromEvent, Observable, shareReplay } from 'rxjs';
import { identity } from 'lodash-es';
import { editor } from 'monaco-editor';
import { slak } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { controlValueStream } from '@cki-utils/form-control-observable';
import CodePrintingMethods = slak.ckompiler.analysis.external.CodePrintingMethods;
import ISAType = slak.ckompiler.backend.ISAType;

export enum Settings {
  TRANSPARENCY = 'TRANSPARENCY',
  HIDE_GRAPH_UI = 'HIDE_GRAPH_UI',
  IS_SPILL_ONLY = 'IS_SPILL_ONLY',
  DEFAULT_FUNCTION_NAME = 'DEFAULT_FUNCTION_NAME',
  CURRENT_TARGET_FUNCTION = 'CURRENT_TARGET_FUNCTION',
  CURRENT_PRINTING_TYPE = 'CURRENT_PRINTING_TYPE',
  ISA_TYPE = 'ISA_TYPE',
  MONACO_VIEW_STATE = 'MONACO_VIEW_STATE',
  MONACO_FONT_SIZE = 'MONACO_FONT_SIZE',
}

export class Setting<T> {
  public readonly formControl: FormControl = new FormControl(this.snapshot);

  public readonly value$: Observable<T> = controlValueStream<T>(this.formControl).pipe(
    distinctUntilChanged(),
    shareReplay({ refCount: false, bufferSize: 1 }),
  );

  constructor(
    public readonly setting: Settings,
    private readonly parse: (value: string | null) => T,
    private readonly stringify: (value: T) => string,
  ) {
    this.formControl.valueChanges.subscribe((value: T) => {
      setItem(this.setting, this.stringify(value));
    });
  }

  public get snapshot(): T {
    return this.parse(getItem(this.setting));
  }

  public update(value: T): void {
    this.formControl.setValue(value);
  }

  public static ofBoolean(settingType: Settings): Setting<boolean> {
    return new Setting(
      settingType,
      value => value === 'true',
      value => `${value}`,
    );
  }
}

export const hasTransparency: Setting<boolean> = Setting.ofBoolean(Settings.TRANSPARENCY);

export const hideGraphUI: Setting<boolean> = Setting.ofBoolean(Settings.HIDE_GRAPH_UI);

export const isSpillOnly: Setting<boolean> = Setting.ofBoolean(Settings.IS_SPILL_ONLY);

export const defaultFunctionName: Setting<string> = new Setting<string>(
  Settings.DEFAULT_FUNCTION_NAME,
  value => value ?? 'main',
  identity,
);

export const currentTargetFunction: Setting<string> = new Setting<string>(
  Settings.CURRENT_TARGET_FUNCTION,
  identity,
  identity,
);

export const currentPrintingType: Setting<CodePrintingMethods> = new Setting<CodePrintingMethods>(
  Settings.CURRENT_PRINTING_TYPE,
  value => value ? CodePrintingMethods.valueOf(value) : CodePrintingMethods.IR_TO_STRING,
  value => value.name,
);

export const isaType: Setting<ISAType> = new Setting<ISAType>(
  Settings.ISA_TYPE,
  value => value ? ISAType.valueOf(value) : ISAType.X64,
  value => value.name,
);

export const monacoViewState: Setting<editor.ICodeEditorViewState | null> = new Setting(
  Settings.MONACO_VIEW_STATE,
  value => JSON.parse(value!) as editor.ICodeEditorViewState | null,
  value => JSON.stringify(value),
);

export const monacoFontSize: Setting<number> = new Setting(
  Settings.MONACO_FONT_SIZE,
  value => parseFloat(value ?? '14'),
  value => `${value}`,
);

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const settings: Setting<any>[] = [
  hasTransparency,
  hideGraphUI,
  isSpillOnly,
  defaultFunctionName,
  currentTargetFunction,
  currentPrintingType,
  isaType,
  monacoViewState,
  monacoFontSize,
];

function generateKey(setting: Settings): string {
  return `ckompiler-setting-${setting}`;
}

function getItem(setting: Settings): string | null {
  return localStorage.getItem(generateKey(setting));
}

function setItem(setting: Settings, value: string): void {
  localStorage.setItem(generateKey(setting), value);
}

fromEvent<StorageEvent>(window, 'storage').subscribe((event: StorageEvent) => {
  if (!event.key) {
    return;
  }

  const setting = settings.find(instance => generateKey(instance.setting) === event.key);

  setting?.update(event.newValue);
});
