import { GraphvizDatum } from './models/graphviz-datum.model';
import { slak } from '@ckompiler/ckompiler';
import CFG = slak.ckompiler.analysis.CFG;
import arrayOf = slak.ckompiler.arrayOf;
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import Variable = slak.ckompiler.analysis.Variable;
import PhiInstruction = slak.ckompiler.analysis.PhiInstruction;
import IRInstruction = slak.ckompiler.analysis.IRInstruction;
import arrayOfIterator = slak.ckompiler.arrayOfIterator;

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
  const regexp = new RegExp(`(${variable.tid.toString()}(?: v\\d+)?)`, 'g');
  let containsVariable = false;
  const replaced = text.replace(regexp, (variable) => {
    containsVariable = true;

    return replacePattern.replace('$1', variable);
  });

  return [replaced, containsVariable];
}

export function getVariableTextAndIndex(
  cfg: CFG,
  isForPhi: boolean,
  irIndex: number | undefined,
  nodeId: number,
  variable: Variable,
): [string, number] {
  const node = getNodeById(cfg, nodeId);
  const nodePhi = arrayOf<PhiInstruction>(node.phi);

  let index: number;
  let irString: string;

  if (irIndex === -1 && nodeId === cfg.startBlock.nodeId) {
    // This is a use of an undefined variable
    // Return index "-1" to target the start block header
    index = -1;
    irString = variable.toString();
  } else if (isForPhi || irIndex === undefined || irIndex === -1) {
    // Definition is in φ
    index = nodePhi.findIndex(phi => phi.variable.identityId === variable.identityId);
    if (index === -1) {
      throw new Error('Cannot find variable in block phis');
    }
    irString = nodePhi[index].toString();
  } else {
    // Definition is in IR
    const irDefinition = arrayOfIterator<IRInstruction>(node.instructions)[irIndex];
    index = nodePhi.length + irIndex;
    irString = irDefinition.toString();
  }

  // +1 due to the BBx: header
  index++;

  return [irString, index];
}

export function runWithVariableVersions<T>(disableVariableVersions: boolean, block: () => T): T {
  const saved = slak.ckompiler.printVariableVersions;
  slak.ckompiler.printVariableVersions = !disableVariableVersions;

  const value = block();

  slak.ckompiler.printVariableVersions = saved;

  return value;
}
