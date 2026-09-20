import { Component, OnInit, Injectable } from '@angular/core';
import { Http } from '@angular/http';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'recent-tacos',
  templateUrl: 'recents.component.html',
  styleUrls: ['./recents.component.css']
})

@Injectable()
export class RecentTacosComponent implements OnInit {
  recentTacos: any;
  favoriteIds: string[] = [];

  constructor(private httpClient: HttpClient) { }

  ngOnInit() {
    this.httpClient.get('http://localhost:8080/api/tacos')
        .subscribe((data: any) => this.recentTacos = (data && data.content) ? data.content : data);

    this.loadFavorites();
  }

  loadFavorites() {
    this.httpClient.get('http://localhost:8080/api/users/me/favorites', { withCredentials: true })
        .subscribe(
          (data: any) => {
            const list = (data && data.content) ? data.content : data;
            if (Array.isArray(list)) {
              this.favoriteIds = list.map((f: any) => f.tacoId || (f.taco ? f.taco.id : ''));
            }
          },
          () => {
            // Silently ignore if unauthenticated
          }
        );
  }

  isFavorite(tacoId: string): boolean {
    return !!tacoId && this.favoriteIds.indexOf(tacoId) !== -1;
  }

  toggleFavorite(taco: any) {
    if (!taco || !taco.id) return;
    const tacoId = taco.id;

    if (this.isFavorite(tacoId)) {
      this.httpClient.delete('http://localhost:8080/api/users/me/favorites/' + tacoId, { withCredentials: true })
          .subscribe(() => {
            this.favoriteIds = this.favoriteIds.filter(id => id !== tacoId);
          });
    } else {
      this.httpClient.put('http://localhost:8080/api/users/me/favorites/' + tacoId, {}, { withCredentials: true })
          .subscribe(() => {
            if (this.favoriteIds.indexOf(tacoId) === -1) {
              this.favoriteIds.push(tacoId);
            }
          });
    }
  }
}
