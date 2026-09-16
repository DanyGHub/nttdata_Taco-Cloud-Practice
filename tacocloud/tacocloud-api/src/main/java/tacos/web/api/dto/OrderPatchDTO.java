package tacos.web.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties (ignoreUnknown = false)
public class OrderPatchDTO {

    private String id;
    private String deliveryName;
    private String deliveryStreet;
    private String deliveryCity;
    private String deliveryState;
    private String deliveryZip;
    
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
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
}