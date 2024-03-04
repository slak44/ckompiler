import { AlterGraphHook, GraphViewHook } from '../models/graph-view-hook.model';

const alterGraph: AlterGraphHook = (graphView, cfg, printingType, graph) => {
  const titles = Array.from(graph.querySelectorAll('title'));
  for (const titleElem of titles) {
    titleElem.textContent = '';
  }
};

export const removeHoverTitles: GraphViewHook = { alterGraph };
