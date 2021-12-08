import { ChangeDetectionStrategy, Component } from '@angular/core';
import { slak } from '@ckompiler/ckompiler';
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import arrayOf = slak.ckompiler.arrayOf;
import jsCompile = slak.ckompiler.jsCompile;

@Component({
  selector: 'cki-graph-view',
  templateUrl: './graph-view.component.html',
  styleUrls: ['./graph-view.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GraphViewComponent {
  public blocks?: BasicBlock[];

  constructor() {
    const cfgs = jsCompile("int main() { int a = 2; int b = a + a + 2; return 1 + b; }");
    if (cfgs == null) {
      console.error("Compilation failed.");
    } else {
      const main = cfgs.find(cfg => cfg.f.name === "main")!;
      this.blocks = arrayOf<BasicBlock>(main.allNodes);
    }
  }
}
