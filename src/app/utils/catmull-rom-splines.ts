/**
 * Create pretty splines, continuous at join points.
 *
 * @param flatPoints array of [x, y] points, flattened
 * @param k tension
 * @see https://codepen.io/osublake/pen/BowJed
 * @see https://en.wikipedia.org/wiki/Centripetal_Catmull%E2%80%93Rom_spline
 */
export function catmullRomSplines(flatPoints: number[], k: number = 1) {
  const size = flatPoints.length;

  if (size === 0) {
    return '';
  }

  const last = size - 4;

  let path = 'M' + [flatPoints[0], flatPoints[1]].join();

  for (let i = 0; i < size - 2; i += 2) {
    const x0 = i ? flatPoints[i - 2] : flatPoints[0];
    const y0 = i ? flatPoints[i - 1] : flatPoints[1];

    const x1 = flatPoints[i + 0];
    const y1 = flatPoints[i + 1];

    const x2 = flatPoints[i + 2];
    const y2 = flatPoints[i + 3];

    const x3 = i !== last ? flatPoints[i + 4] : x2;
    const y3 = i !== last ? flatPoints[i + 5] : y2;

    const cp1x = x1 + (x2 - x0) / 6 * k;
    const cp1y = y1 + (y2 - y0) / 6 * k;

    const cp2x = x2 - (x3 - x1) / 6 * k;
    const cp2y = y2 - (y3 - y1) / 6 * k;

    path += 'C' + [cp1x, cp1y, cp2x, cp2y, x2, y2].join();
  }

  return path;
}
