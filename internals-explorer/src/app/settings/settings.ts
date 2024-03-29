import { distinctUntilChanged, fromEvent, Observable, shareReplay } from 'rxjs';
import { identity } from 'lodash-es';
import type { editor } from 'monaco-editor';
import { CodePrintingMethods, ISAType } from '@ckompiler/ckompiler';
import { FormControl } from '@angular/forms';
import { controlValueStream } from '@cki-utils/form-control-observable';
import { debounceAfterFirst } from '@cki-utils/debounce-after-first';
import { ZoomTransform } from 'd3-zoom';
import { ViewStateMetadata, ZoomTransformDto } from './models/view-state.model';

export enum Settings {
  SOURCE_CODE = 'SOURCE_CODE',
  TRANSPARENCY = 'TRANSPARENCY',
  HIDE_GRAPH_UI = 'HIDE_GRAPH_UI',
  IS_SPILL_ONLY = 'IS_SPILL_ONLY',
  DEFAULT_FUNCTION_NAME = 'DEFAULT_FUNCTION_NAME',
  CURRENT_TARGET_FUNCTION = 'CURRENT_TARGET_FUNCTION',
  CURRENT_PRINTING_TYPE = 'CURRENT_PRINTING_TYPE',
  ISA_TYPE = 'ISA_TYPE',
  MONACO_VIEW_STATE = 'MONACO_VIEW_STATE',
  MONACO_FONT_SIZE = 'MONACO_FONT_SIZE',
  GRAPH_VIEW_TRANSFORM = 'GRAPH_VIEW_TRANSFORM',
  GRAPH_VIEW_SELECTED_ID = 'GRAPH_VIEW_SELECTED_ID',
  PHI_INSERTION_TRANSFORM = 'PHI_INSERTION_TRANSFORM',
  PHI_INSERTION_SELECTED_ID = 'PHI_INSERTION_SELECTED_ID',
  PHI_INSERTION_VARIABLE_ID = 'PHI_INSERTION_VARIABLE_ID',
  PHI_INSERTION_STEP_IDX = 'PHI_INSERTION_STEP_IDX',
  VARIABLE_RENAME_TRANSFORM = 'VARIABLE_RENAME_TRANSFORM',
  VARIABLE_RENAME_SELECTED_ID = 'VARIABLE_RENAME_SELECTED_ID',
  VARIABLE_RENAME_VARIABLE_ID = 'VARIABLE_RENAME_VARIABLE_ID',
  VARIABLE_RENAME_STEP_IDX = 'VARIABLE_RENAME_STEP_IDX',
  RECENT_PUBLIC_LINKS = 'RECENT_PUBLIC_LINKS',
}

export class Setting<T> {
  public readonly formControl: FormControl<T> = new FormControl<T>(this.snapshot, { nonNullable: true });

  public readonly value$: Observable<T> = controlValueStream<T>(this.formControl).pipe(
    distinctUntilChanged(),
    shareReplay({ refCount: false, bufferSize: 1 }),
  );

  constructor(
    public readonly setting: Settings,
    private readonly parse: (value: string | null) => T,
    private readonly stringify: (value: T) => string,
  ) {
    this.formControl.valueChanges.pipe(
      debounceAfterFirst(500),
    ).subscribe((value: T) => {
      setItem(this.setting, this.stringify(value));
    });
  }

  public get snapshot(): T {
    try {
      return this.parse(getItem(this.setting));
    } catch (e) {
      console.error(e);
      return this.parse(null);
    }
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

  public static ofInteger(settingType: Settings): Setting<number | null> {
    return new Setting(
      settingType,
      value => {
        const int = parseInt(value ?? '', 10);
        if (isNaN(int)) {
          return null;
        }
        return int;
      },
      value => `${value}`,
    );
  }

  public static ofZoomTransform(settingType: Settings): Setting<ZoomTransform | null> {
    return new Setting<ZoomTransform | null>(
      settingType,
      value => {
        if (!value) {
          return null;
        }
        const data = JSON.parse(value) as Partial<ZoomTransformDto>;
        if (data && typeof data.k === 'number' && typeof data.x === 'number' && typeof data.y === 'number') {
          return new ZoomTransform(data.k, data.x, data.y);
        } else {
          return null;
        }
      },
      value => {
        if (!value) {
          return '';
        }
        return JSON.stringify({ k: value.k, x: value.x, y: value.y });
      },
    );
  }
}

export const sourceCode: Setting<string> = new Setting<string>(
  Settings.SOURCE_CODE,
  value => value ?? '',
  identity,
);

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

export const graphViewTransform: Setting<ZoomTransform | null> = Setting.ofZoomTransform(Settings.GRAPH_VIEW_TRANSFORM);

export const graphViewSelectedId: Setting<number | null> = Setting.ofInteger(Settings.GRAPH_VIEW_SELECTED_ID);

export const phiInsertionTransform: Setting<ZoomTransform | null> =
  Setting.ofZoomTransform(Settings.PHI_INSERTION_TRANSFORM);

export const phiInsertionSelectedId: Setting<number | null> = Setting.ofInteger(Settings.PHI_INSERTION_SELECTED_ID);

export const phiInsertionVariableId: Setting<number | null> = Setting.ofInteger(Settings.PHI_INSERTION_VARIABLE_ID);

export const phiInsertionStepIdx: Setting<number | null> = Setting.ofInteger(Settings.PHI_INSERTION_STEP_IDX);

export const variableRenameTransform: Setting<ZoomTransform | null> =
  Setting.ofZoomTransform(Settings.VARIABLE_RENAME_TRANSFORM);

export const variableRenameSelectedId: Setting<number | null> = Setting.ofInteger(Settings.VARIABLE_RENAME_SELECTED_ID);

export const variableRenameVariableId: Setting<number | null> = Setting.ofInteger(Settings.VARIABLE_RENAME_VARIABLE_ID);

export const variableRenameStepIdx: Setting<number | null> = Setting.ofInteger(Settings.VARIABLE_RENAME_STEP_IDX);

export const recentPublicShareLinks: Setting<ViewStateMetadata[]> =  new Setting(
  Settings.RECENT_PUBLIC_LINKS,
  value => JSON.parse(value || '[]') as ViewStateMetadata[],
  value => JSON.stringify(value),
);

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const settings: Setting<any>[] = [
  sourceCode,
  hasTransparency,
  hideGraphUI,
  isSpillOnly,
  defaultFunctionName,
  currentTargetFunction,
  currentPrintingType,
  isaType,
  monacoViewState,
  monacoFontSize,
  graphViewTransform,
  graphViewSelectedId,
  phiInsertionTransform,
  phiInsertionSelectedId,
  phiInsertionVariableId,
  phiInsertionStepIdx,
  variableRenameTransform,
  variableRenameSelectedId,
  variableRenameVariableId,
  variableRenameStepIdx,
  recentPublicShareLinks,
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
