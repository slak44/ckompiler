import type { editor } from 'monaco-editor';

declare global {
  interface Window {
    __hpcc_wasmFolder?: string;
    monaco: {
      editor: typeof editor
    };
  }
}

export {};
