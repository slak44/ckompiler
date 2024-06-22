// https://michalzalecki.com/nominal-typing-in-typescript/
export type Brand<K, T> = K & { __brand: T };
