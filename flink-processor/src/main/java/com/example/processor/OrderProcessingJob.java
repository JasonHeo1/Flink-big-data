package main.java.com.example.processor;

import com.example.processor.model.TradeOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.rabbitmq.source.RabbitMQSource;
import org.apache.flink.connector.rabbitmq.source.RabbitMQSourceOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Properties;

public class OrderProcessingJob {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static Properties config = new Properties();

    static {
        try (InputStream input = OrderProcessingJob.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            config.load(input);
        } catch (Exception e) {
            throw new RuntimeException("Error loading configuration", e);
        }
    }

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(Long.parseLong(config.getProperty("flink.checkpoint.interval")));
        env.setParallelism(Integer.parseInt(config.getProperty("flink.parallelism")));

        // 配置RabbitMQ源
        RabbitMQSourceOptions options = RabbitMQSourceOptions.builder()
                .setUri(String.format("amqp://%s:%s@%s:%s/%s",
                        config.getProperty("rabbitmq.username"),
                        config.getProperty("rabbitmq.password"),
                        config.getProperty("rabbitmq.host"),
                        config.getProperty("rabbitmq.port"),
                        config.getProperty("rabbitmq.virtual-host")))
                .setQueueName(config.getProperty("rabbitmq.queue"))
                .setExchangeName(config.getProperty("rabbitmq.exchange"))
                .setRoutingKey(config.getProperty("rabbitmq.routing-key"))
                .setDeserializer(new SimpleStringSchema())
                .build();

        // 创建RabbitMQ数据源
        DataStream<String> rabbitStream = env.fromSource(
                RabbitMQSource.source(options),
                WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofSeconds(5)),
                "RabbitMQ Source");

        // 解析JSON并过滤小额订单
        DataStream<TradeOrder> orderStream = rabbitStream
                .map(new MapFunction<String, TradeOrder>() {
                    @Override
                    public TradeOrder map(String value) throws Exception {
                        return objectMapper.readValue(value, TradeOrder.class);
                    }
                })
                .filter(new FilterFunction<TradeOrder>() {
                    @Override
                    public boolean filter(TradeOrder order) {
                        return order.getTotalAmount() >= Double.parseDouble(config.getProperty("order.min-amount"));
                    }
                });

        // 保存原始订单数据到TiDB
        orderStream.addSink(JdbcSink.sink(
                "INSERT INTO trade_orders (order_id, symbol, price, quantity, timestamp) VALUES (?, ?, ?, ?, ?)",
                (statement, order) -> {
                    statement.setString(1, order.getOrderId());
                    statement.setString(2, order.getSymbol());
                    statement.setDouble(3, order.getPrice());
                    statement.setDouble(4, order.getQuantity());
                    statement.setLong(5, order.getTimestamp());
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(1000)
                        .withBatchIntervalMs(200)
                        .withMaxRetries(3)
                        .build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(config.getProperty("tidb.url"))
                        .withDriverName(config.getProperty("tidb.driver"))
                        .withUsername(config.getProperty("tidb.username"))
                        .withPassword(config.getProperty("tidb.password"))
                        .build()
        ));

        // 按交易对分组，计算每分钟统计数据
        orderStream
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<TradeOrder>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                                .withTimestampAssigner((event, timestamp) -> event.getTimestamp()))
                .keyBy(order -> order.getSymbol())
                .window(TumblingEventTimeWindows.of(Time.seconds(
                        Long.parseLong(config.getProperty("order.window.size")))))
                .process(new ProcessWindowFunction<TradeOrder, Object, String, TimeWindow>() {
                    @Override
                    public void process(String symbol,
                                      Context context,
                                      Iterable<TradeOrder> orders,
                                      Collector<Object> out) {
                        double totalAmount = 0;
                        double totalPrice = 0;
                        int count = 0;

                        for (TradeOrder order : orders) {
                            totalAmount += order.getTotalAmount();
                            totalPrice += order.getPrice();
                            count++;
                        }

                        if (count > 0) {
                            try {
                                // 插入分钟级统计数据
                                String sql = "INSERT INTO trade_stats_minute "
                                        + "(symbol, minute_time, total_amount, avg_price, trade_count) "
                                        + "VALUES (?, ?, ?, ?, ?)";
                                env.addSink(JdbcSink.sink(
                                        sql,
                                        (statement, context1) -> {
                                            statement.setString(1, symbol);
                                            statement.setTimestamp(2,
                                                    new Timestamp(context.window().getStart()));
                                            statement.setDouble(3, totalAmount);
                                            statement.setDouble(4, totalPrice / count);
                                            statement.setInt(5, count);
                                        },
                                        JdbcExecutionOptions.builder()
                                                .withBatchSize(1)
                                                .withBatchIntervalMs(1000)
                                                .withMaxRetries(3)
                                                .build(),
                                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                                .withUrl(config.getProperty("tidb.url"))
                                                .withDriverName(config.getProperty("tidb.driver"))
                                                .withUsername(config.getProperty("tidb.username"))
                                                .withPassword(config.getProperty("tidb.password"))
                                                .build()
                                ));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });

        env.execute("Trade Order Processing Job");
    }
}