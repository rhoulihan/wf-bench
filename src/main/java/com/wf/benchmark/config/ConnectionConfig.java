package com.wf.benchmark.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.util.concurrent.TimeUnit;

public class ConnectionConfig {

    private String connectionString;
    private String database = "benchmark";
    private int connectionPoolSize = 10;
    private int connectionTimeoutMs = 30000;
    private int socketTimeoutMs = 60000;

    public ConnectionConfig() {
    }

    public ConnectionConfig(String connectionString, String database) {
        this.connectionString = connectionString;
        this.database = database;
    }

    public MongoClient createClient() {
        ConnectionString connString = new ConnectionString(connectionString);

        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(connString)
            .applyToConnectionPoolSettings(builder -> builder
                .maxSize(connectionPoolSize)
                .minSize(1)
                .maxWaitTime(connectionTimeoutMs, TimeUnit.MILLISECONDS))
            .applyToSocketSettings(builder -> builder
                .connectTimeout(connectionTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS))
            .build();

        return MongoClients.create(settings);
    }

    // Getters and setters
    public String getConnectionString() {
        return connectionString;
    }

    public void setConnectionString(String connectionString) {
        this.connectionString = connectionString;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public int getConnectionPoolSize() {
        return connectionPoolSize;
    }

    public void setConnectionPoolSize(int connectionPoolSize) {
        this.connectionPoolSize = connectionPoolSize;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }
}
