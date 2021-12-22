import * as Monaco from 'monaco-editor';
import { Observable, ReplaySubject } from 'rxjs';

const monacoLoadSubject: ReplaySubject<typeof Monaco> = new ReplaySubject(1);

export const monacoLoaded$: Observable<typeof Monaco> = monacoLoadSubject;

export async function monacoLoader(monaco: typeof Monaco): Promise<typeof Monaco> {
  monacoLoadSubject.next(monaco);

  const theme = await import('node_modules/vscode-theme-darcula/themes/darcula.json');

  // https://stackoverflow.com/questions/65959169/how-to-use-a-vsc-theme-in-monaco-editor
  const tokenRules = (theme.tokenColors || []).flatMap(value => {
    const scopeValue = value.scope || [];
    const scopes = Array.isArray(scopeValue) ? scopeValue : scopeValue.split(",");
    return scopes.map((scope: string) => ({
      token: scope,
      foreground: value.settings.foreground,
      fontStyle: value.settings.fontStyle,
    }));
  });

  const definedTheme: Monaco.editor.IStandaloneThemeData = {
    base: theme.type === 'light' ? 'vs' : 'vs-dark',
    inherit: true,
    rules: tokenRules,
    colors: theme.colors || {}
  };

  monaco.editor.defineTheme('darcula', definedTheme);
  monaco.editor.setTheme('darcula');

  return monaco;
}
