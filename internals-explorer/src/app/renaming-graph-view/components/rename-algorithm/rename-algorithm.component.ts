import { Component, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { phaseInOut } from '@cki-utils/phase-in-out';

@Component({
  selector: 'cki-rename-algorithm',
  templateUrl: './rename-algorithm.component.html',
  styleUrls: ['./rename-algorithm.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [phaseInOut],
})
export class RenameAlgorithmComponent implements OnInit {

  constructor() { }

  ngOnInit(): void {
  }

}
