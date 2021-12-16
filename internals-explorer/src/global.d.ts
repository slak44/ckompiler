declare global {
  interface Window {
    __hpcc_wasmFolder?: string;
    monaco: any;
  }
}

export {};
