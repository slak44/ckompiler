export enum Settings {
  TRANSPARENCY = 'transparency'
}

export function generateKey(setting: Settings): string {
  return `ckompiler-setting-${setting}`;
}

function getItem(setting: Settings): string | null {
  return localStorage.getItem(generateKey(setting));
}

export function hasTransparency(setting: Settings): boolean {
  return getItem(setting) === 'true';
}
