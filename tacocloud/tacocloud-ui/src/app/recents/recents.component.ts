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
  ratings: { [key: string]: any } = {};

  constructor(private httpClient: HttpClient) { }

  ngOnInit() {
    this.httpClient.get('http://localhost:8080/api/tacos')
        .subscribe((data: any) => {
          this.recentTacos = (data && data.content) ? data.content : data;
          if (Array.isArray(this.recentTacos)) {
            this.recentTacos.forEach((taco: any) => {
              if (taco && taco.id) {
                this.loadRating(taco.id);
              }
            });
          }
        });

    this.loadFavorites();
  }

  loadRating(tacoId: string) {
    this.httpClient.get('http://localhost:8080/api/tacos/' + tacoId + '/rating', { withCredentials: true })
        .subscribe(
          (data: any) => {
            this.ratings[tacoId] = data;
          },
          () => {}
        );
  }

  rateTaco(tacoId: string, score: number) {
    if (!tacoId || score < 1 || score > 5) return;
    this.httpClient.put('http://localhost:8080/api/tacos/' + tacoId + '/rating', { score: score }, { withCredentials: true })
        .subscribe(
          (data: any) => {
            this.ratings[tacoId] = data;
          },
          (err: any) => {
            if (err && err.status === 401) {
              alert('Debes iniciar sesión para calificar este taco.');
            }
          }
        );
  }

  getRating(tacoId: string): any {
    return this.ratings[tacoId] || null;
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
