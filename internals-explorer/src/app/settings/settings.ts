import { BehaviorSubject, fromEvent, Observable } from 'rxjs';
import { identity } from 'lodash-es';
import { editor } from 'monaco-editor';

export enum Settings {
  TRANSPARENCY = 'TRANSPARENCY',
  DEFAULT_FUNCTION_NAME = 'DEFAULT_FUNCTION_NAME',
  CURRENT_TARGET_FUNCTION = 'CURRENT_TARGET_FUNCTION',
  MONACO_VIEW_STATE = 'MONACO_VIEW_STATE',
  MONACO_FONT_SIZE = 'MONACO_FONT_SIZE',
}

export class Setting<T> {
  private readonly valueSubject: BehaviorSubject<T> = new BehaviorSubject<T>(this.snapshot);

  public readonly value$: Observable<T> = this.valueSubject;

  constructor(
    public readonly setting: Settings,
    private readonly parse: (value: string | null) => T,
    private readonly stringify: (value: T) => string,
  ) {
  }

  public get snapshot(): T {
    return this.parse(getItem(this.setting));
  }

  public update(value: T): void {
    setItem(this.setting, this.stringify(value));
    this.valueSubject.next(value);
  }
}

export const hasTransparency: Setting<boolean> = new Setting(
  Settings.TRANSPARENCY,
  value => value === 'true',
  value => `${value}`,
);

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
  defaultFunctionName,
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
