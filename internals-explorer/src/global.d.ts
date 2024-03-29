import type { Environment } from 'monaco-editor';

declare global {
  interface Window {
    __hpcc_wasmFolder?: string;
    MonacoEnvironment?: Environment;
  }
}

export {};
