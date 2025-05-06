package com.example.producer.model;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class Order {
    private String orderId;
    private String symbol;
    private double price;
    private double quantity;
    private long timestamp;

    public double getTotalAmount() {
        return price * quantity;
    }
}