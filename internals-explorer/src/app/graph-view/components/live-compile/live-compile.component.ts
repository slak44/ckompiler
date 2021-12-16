import { Component, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Component({
  selector: 'cki-live-compile',
  templateUrl: './live-compile.component.html',
  styleUrls: ['./live-compile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveCompileComponent implements OnInit {
  public readonly source$: Observable<string> = this.httpClient.get('/assets/default.c', { responseType: 'text' });

  constructor(
    private httpClient: HttpClient,
  ) {
  }

  public ngOnInit(): void {
  }
}
