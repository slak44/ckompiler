import { BehaviorSubject, fromEvent, Observable } from 'rxjs';

export enum Settings {
  TRANSPARENCY = 'transparency'
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const settings: Setting<any>[] = [
  hasTransparency,
];

function generateKey(setting: Settings): string {
  return `ckompiler-setting-${setting}`;
}

function getItem(setting: Settings): string | null {
  return localStorage.getItem(generateKey(setting));
}

function setItem(setting: Settings, value: string) {
  localStorage.setItem(generateKey(setting), value);
}

fromEvent<StorageEvent>(window, 'storage').subscribe((event: StorageEvent) => {
  if (!event.key) {
    return;
  }

  const setting = settings.find(instance => generateKey(instance.setting) === event.key);

  setting?.update(event.newValue);
});
