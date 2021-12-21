import type { editor } from 'monaco-editor';

export async function monacoThemeLoader(): Promise<void> {
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

  const definedTheme: editor.IStandaloneThemeData = {
    base: theme.type === 'light' ? 'vs' : 'vs-dark',
    inherit: true,
    rules: tokenRules,
    colors: theme.colors || {}
  };

  /* eslint-disable @typescript-eslint/no-unnecessary-type-assertion */
  window.monaco!.editor.defineTheme('darcula', definedTheme);
  window.monaco!.editor.setTheme('darcula');
}
