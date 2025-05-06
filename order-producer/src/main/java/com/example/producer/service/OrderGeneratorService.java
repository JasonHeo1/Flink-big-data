package com.example.producer.service;

import com.example.producer.model.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderGeneratorService {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();
    private final AtomicLong orderIdCounter = new AtomicLong(1);

    @Value("${app.producer.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.producer.rabbitmq.routing-key}")
    private String routingKey;

    @Value("#{${app.producer.simulation.initial-prices}}")
    private Map<String, Double> initialPrices;

    @Value("${app.producer.symbols}")
    private List<String> symbols;

    @Value("${app.producer.simulation.price-fluctuation}")
    private double priceFluctuation;

    @Scheduled(fixedDelayString = "${app.producer.simulation.orders-per-second:100}")
    public void generateAndSendOrder() {
        try {
            String symbol = getRandomSymbol();
            double basePrice = initialPrices.get(symbol);
            double priceChange = (random.nextDouble() - 0.5) * 2 * (basePrice * priceFluctuation / 100);
            double currentPrice = basePrice + priceChange;

            Order order = Order.builder()
                    .orderId(generateOrderId())
                    .symbol(symbol)
                    .price(currentPrice)
                    .quantity(0.001 + random.nextDouble() * 0.099) // 随机数量0.001-0.1
                    .timestamp(Instant.now().toEpochMilli())
                    .build();

            String message = objectMapper.writeValueAsString(order);
            rabbitTemplate.convertAndSend(exchange, routingKey, message);

            log.debug("Generated order: {}", order);
        } catch (Exception e) {
            log.error("Error generating order", e);
        }
    }

    private String getRandomSymbol() {
        return symbols.get(random.nextInt(symbols.size()));
    }

    private String generateOrderId() {
        return String.format("%d%013d", 
            Instant.now().getEpochSecond(),
            orderIdCounter.getAndIncrement() % 10000000000000L
        );
    }
}