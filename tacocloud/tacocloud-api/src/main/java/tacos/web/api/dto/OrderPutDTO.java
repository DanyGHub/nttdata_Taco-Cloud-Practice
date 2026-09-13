package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tacos.Taco;

@JsonIgnoreProperties (ignoreUnknown = false)
public class OrderPutDTO {

    private String id;
    private String deliveryName;
    private String deliveryStreet;
    private String deliveryCity;
    private String deliveryState;
    private String deliveryZip;
    private List<Taco> tacos = new ArrayList<>();

    public String getDeliveryName() {
        return deliveryName;
    }
    public void setDeliveryName(String deliveryName) {
        this.deliveryName = deliveryName;
    }
    public String getDeliveryStreet() {
        return deliveryStreet;
    }
    public void setDeliveryStreet(String deliveryStreet) {
        this.deliveryStreet = deliveryStreet;
    }
    public String getDeliveryCity() {
        return deliveryCity;
    }
    public void setDeliveryCity(String deliveryCity) {
        this.deliveryCity = deliveryCity;
    }
    public String getDeliveryState() {
        return deliveryState;
    }
    public void setDeliveryState(String deliveryState) {
        this.deliveryState = deliveryState;
    }
    public String getDeliveryZip() {
        return deliveryZip;
    }
    public void setDeliveryZip(String deliveryZip) {
        this.deliveryZip = deliveryZip;
    }
    public List<Taco> getTacos() {
        return tacos;
    }
    public void setTacos(List<Taco> tacos) {
        this.tacos = tacos;
    }
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
}