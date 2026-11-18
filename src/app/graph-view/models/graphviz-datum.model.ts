/**
 * This is found on edges.
 */
export interface BaseGraphvizDatum {
  id: string;
  key: string;
  parent: GraphvizDatum;
  tag: string;
  children: GraphvizDatum[];
  attributes: Record<string, string>;
}

/**
 * This is found on nodes.
 */
export interface GraphvizDatum extends BaseGraphvizDatum {
  text: string;
  bbox: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
  center: {
    x: number;
    y: number;
  };
}
