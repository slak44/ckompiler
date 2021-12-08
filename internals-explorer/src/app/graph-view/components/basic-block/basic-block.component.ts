import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { slak } from '@ckompiler/ckompiler';
import BasicBlock = slak.ckompiler.analysis.BasicBlock;
import irToString = slak.ckompiler.analysis.irToString;

@Component({
  selector: 'cki-basic-block',
  templateUrl: './basic-block.component.html',
  styleUrls: ['./basic-block.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BasicBlockComponent {
  @Input() public basicBlock!: BasicBlock;

  constructor() {
  }

  public irToString(): string {
    return irToString(this.basicBlock);
  }
}
