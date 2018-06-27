package com.landoop.lenses.topology.client.kafka.spark;

import com.landoop.lenses.topology.client.NodeType;
import com.landoop.lenses.topology.client.Representation;
import com.landoop.lenses.topology.client.Topology;
import com.landoop.lenses.topology.client.TopologyBuilder;
import com.landoop.lenses.topology.client.TopologyClient;
import com.landoop.lenses.topology.client.kafka.metrics.KafkaTopologyClient;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.OutputMode;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

public class App {

    public static void main(String[] args) throws StreamingQueryException, IOException {

        Topology topology = TopologyBuilder.start("my app")
                .withTopic("wordcount-input")
                .withDescription("Raw lines of text")
                .withRepresentation(Representation.TABLE)
                .finish()
                .withNode("groupby", NodeType.GROUPBY)
                .withDescription("Group by value")
                .withRepresentation(Representation.TABLE)
                .withParent("wordcount-input")
                .finish()
                .withNode("count", NodeType.COUNT)
                .withDescription("Count value")
                .withRepresentation(Representation.TABLE)
                .withParent("groupby")
                .finish()
                .withTopic("console")
                .withParent("count")
                .withDescription("Words put onto the output")
                .withRepresentation(Representation.TABLE)
                .finish()
                .build();

        Properties topologyProps = new Properties();
        topologyProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        TopologyClient client = KafkaTopologyClient.create(topologyProps);
        client.register(topology);

        SparkSession spark = SparkSession
                .builder()
                .master("local[4]")
                .appName("kafka-topology")
                .getOrCreate();

        Dataset<Row> words = spark
                .readStream()
                .format("lenses-kafka")
                .option("kafka.bootstrap.servers", "localhost:9092")
                .option("lenses.topology.description", topology.getDescription())
                .option("subscribe", "wordcount-input")
                .load();

        Dataset<Row> wordCounts = words.selectExpr("CAST(value AS STRING)").flatMap(
                (FlatMapFunction<Row, String>) row -> Arrays.stream(row.getAs("value").toString().split(" ")).iterator(),
                Encoders.STRING()
        ).groupBy("value").count();

        StreamingQuery query = wordCounts.writeStream()
                .outputMode(OutputMode.Complete())
                .format("console")
                .start();

        query.awaitTermination();
    }
}