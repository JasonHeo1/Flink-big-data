package main.java.com.example.processor.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeOrder {
    private String orderId;
    private String symbol;
    private double price;
    private double quantity;
    private long timestamp;

    public double getTotalAmount() {
        return price * quantity;
    }

    @Override
    public String toString() {
        return String.format("TradeOrder(orderId=%s, symbol=%s, price=%.8f, quantity=%.8f, timestamp=%d)",
                orderId, symbol, price, quantity, timestamp);
    }
}