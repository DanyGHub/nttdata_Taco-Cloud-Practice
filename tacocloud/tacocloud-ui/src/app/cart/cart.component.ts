import { Component, OnInit, Injectable } from '@angular/core';
import { CartService } from './cart-service';
import { HttpClient, HttpHeaders } from '@angular/common/http';

@Component({
  selector: 'taco-cart',
  templateUrl: 'cart.component.html',
  styleUrls: ['./cart.component.css']
})

@Injectable()
export class CartComponent implements OnInit {

  model: any = {
    deliveryName: '',
    deliveryStreet: '',
    deliveryCity: '',
    deliveryState: '',
    deliveryZip: '',
    ccNumber: '',
    ccExpiration: '',
    ccCVV: '',
    tacos: [] as any[],
    items: [] as any[]
  };

  myOrders: any[] = [];
  selectedOrderDetail: any = null;
  orderSuccessMessage: string = '';

  constructor(private cart: CartService, private httpClient: HttpClient) {
    this.cart = cart;
  }

  ngOnInit() {
    this.loadMyOrders();
  }

  loadMyOrders() {
    this.httpClient.get('http://localhost:8080/api/users/me/orders?page=0&size=5', { withCredentials: true })
      .subscribe(
        (data: any) => {
          this.myOrders = (data && data.content) ? data.content : (Array.isArray(data) ? data : []);
        },
        () => {
          this.myOrders = [];
        }
      );
  }

  viewOrderDetail(orderId: string) {
    if (!orderId) return;
    this.httpClient.get('http://localhost:8080/api/users/me/orders/' + orderId, { withCredentials: true })
      .subscribe(
        (data: any) => {
          this.selectedOrderDetail = data;
        },
        () => {
          this.selectedOrderDetail = null;
        }
      );
  }

  closeOrderDetail() {
    this.selectedOrderDetail = null;
  }

  get cartItems() {
    return this.cart.getItemsInCart();
  }

  get cartTotal() {
    return this.cart.getCartTotal();
  }

  onSubmit() {
    this.orderSuccessMessage = '';
    this.model.tacos = [];
    this.model.items = [];
    this.cart.getItemsInCart().forEach(cartItem => {
      const qty = Number(cartItem.quantity) || 1;
      if (qty > 0) {
        this.model.items.push({
          taco: cartItem.taco,
          quantity: qty
        });
        this.model.tacos.push(cartItem.taco);
      }
    });

    this.httpClient.post(
        'http://localhost:8080/api/orders',
        this.model, {
            headers: new HttpHeaders().set('Content-type', 'application/json')
                    .set('Accept', 'application/json'),
            withCredentials: true
        }).subscribe(
          r => {
            this.cart.emptyCart();
            this.orderSuccessMessage = '¡Orden creada exitosamente!';
            this.loadMyOrders();
          },
          err => {
            alert('Error al procesar la orden.');
          }
        );

    // TODO: Do something after this...navigate to a thank you page or something
  }

}
