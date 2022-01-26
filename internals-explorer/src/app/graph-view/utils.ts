import { GraphvizDatum } from './models/graphviz-datum.model';
import { slak } from '@ckompiler/ckompiler';
import CFG = slak.ckompiler.analysis.CFG;
import arrayOf = slak.ckompiler.arrayOf;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;

export function getDatumNodeId(datum: GraphvizDatum): number {
  const match = datum.parent.key.match(/^node(\d+)$/);
  if (!match) return NaN;
  return parseInt(match[1], 10);
}

export function getNodeById(cfg: CFG, nodeId: number): BasicBlock {
  return arrayOf<BasicBlock>(cfg.nodes).find(node => node.nodeId === nodeId)!;
}

export function setClassIf(e: Element, className: string, cond: boolean): void {
  if (cond) {
    e.classList.add(className);
  } else {
    e.classList.remove(className);
  }
}
