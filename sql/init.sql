-- 创建数据库
CREATE DATABASE IF NOT EXISTS trade_db;
USE trade_db;

-- 创建原始订单表
CREATE TABLE IF NOT EXISTS trade_orders (
    order_id VARCHAR(20) PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    price DECIMAL(20,8) NOT NULL,
    quantity DECIMAL(20,8) NOT NULL,
    timestamp BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建每分钟聚合结果表
CREATE TABLE IF NOT EXISTS trade_stats_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    minute_time TIMESTAMP NOT NULL,
    total_amount DECIMAL(30,8) NOT NULL,
    avg_price DECIMAL(20,8) NOT NULL,
    trade_count INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_minute (symbol, minute_time)
);

-- 创建索引
CREATE INDEX idx_orders_timestamp ON trade_orders(timestamp);
CREATE INDEX idx_orders_symbol ON trade_orders(symbol);