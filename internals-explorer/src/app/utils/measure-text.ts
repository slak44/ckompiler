const canvas = document.createElement('canvas');
const ctx = canvas.getContext('2d')!;
ctx.font = `16px "Fira Code"`;

export function measureTextAscent(text: string): number {
  const metrics = ctx.measureText(text);
  return metrics.actualBoundingBoxAscent;
}
