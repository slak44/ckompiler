import { Component, OnInit, ChangeDetectionStrategy } from '@angular/core';

@Component({
  selector: 'cki-live-compile',
  templateUrl: './live-compile.component.html',
  styleUrls: ['./live-compile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveCompileComponent implements OnInit {
  constructor() {
  }

  public ngOnInit(): void {
  }
}
