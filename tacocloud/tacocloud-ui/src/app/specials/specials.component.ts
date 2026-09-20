import { Component, OnInit, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'taco-specials',
  templateUrl: 'specials.component.html'
})
@Injectable()
export class SpecialsComponent implements OnInit {
  special: any = null;
  loading: boolean = true;
  errorMessage: string = '';
  topTacos: any[] = [];
  loadingTop: boolean = true;

  constructor(private httpClient: HttpClient) { }

  ngOnInit() {
    this.httpClient.get('http://localhost:8080/api/tacos/today')
      .subscribe(
        (data: any) => {
          this.special = data;
          this.loading = false;
        },
        (error: any) => {
          this.loading = false;
          this.errorMessage = 'No hay taco del día disponible en este momento.';
        }
      );

    this.loadTopTacos();
  }

  loadTopTacos() {
    this.httpClient.get('http://localhost:8080/api/tacos/top?limit=10')
      .subscribe(
        (data: any) => {
          this.topTacos = Array.isArray(data) ? data : [];
          this.loadingTop = false;
        },
        () => {
          this.loadingTop = false;
        }
      );
  }
}
