import { ChangeDetectionStrategy, Component, HostBinding, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'cki-broadcaster-mouse',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './broadcaster-mouse.component.html',
  styleUrls: ['./broadcaster-mouse.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BroadcasterMouseComponent {
  @Input()
  @HostBinding('class.visible')
  public isVisible: boolean = false;

  @Input()
  @HostBinding('style.left')
  public left: string = '0px';

  @Input()
  @HostBinding('style.top')
  public top: string = '0px';

  @Input()
  @HostBinding('style.--broadcaster-name')
  public broadcasterName: string = '';
}
