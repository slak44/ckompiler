import { GraphvizDatum } from './models/graphviz-datum.model';
import { slak } from '@ckompiler/ckompiler';
import CFG = slak.ckompiler.analysis.CFG;
import arrayOf = slak.ckompiler.arrayOf;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import Variable = slak.ckompiler.analysis.Variable;

export function getPolyDatumNodeId(datum: GraphvizDatum): number {
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

export function replaceVarInText(variable: Variable, text: string): [string, boolean] {
  const replacePattern = '<span class="highlight-variable">$1</span>';
  const regexp = new RegExp(`(${variable.tid.toString()}(?: ${variable.versionString()})?)`, 'g');
  let containsVariable = false;
  const replaced = text.replace(regexp, (variable) => {
    containsVariable = true;

    return replacePattern.replace('$1', variable);
  });

  return [replaced, containsVariable];
}
