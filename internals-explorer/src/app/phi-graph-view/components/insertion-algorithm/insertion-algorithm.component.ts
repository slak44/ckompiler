import { ChangeDetectionStrategy, Component } from '@angular/core';
import { map, Observable } from 'rxjs';
import { PhiInsertionStateService } from '../../services/phi-insertion-state.service';
import { phaseInOut } from '@cki-utils/phase-in-out';

@Component({
  selector: 'cki-insertion-algorithm',
  templateUrl: './insertion-algorithm.component.html',
  styleUrls: ['./insertion-algorithm.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [phaseInOut],
})
export class InsertionAlgorithmComponent {
  public readonly variableName$: Observable<string> = this.phiInsertionStateService.targetVariable$.pipe(
    map(variable => variable.name),
  );

  constructor(
    private phiInsertionStateService: PhiInsertionStateService,
  ) {
  }
}
