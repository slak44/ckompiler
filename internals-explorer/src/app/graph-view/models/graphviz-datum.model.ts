export interface GraphvizDatum {
  id: string;
  key: string;
  parent: GraphvizDatum;
  tag: string;
  text: string;
  children: GraphvizDatum[];
  attributes: Record<string, string>;
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
