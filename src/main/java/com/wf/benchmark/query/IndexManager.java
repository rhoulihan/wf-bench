package com.wf.benchmark.query;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.wf.benchmark.config.QueryConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Manages index creation and verification for query benchmarks.
 */
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final MongoClient client;
    private final QueryConfig config;

    public IndexManager(MongoClient client, QueryConfig config) {
        this.client = client;
        this.config = config;
    }

    public void createIndexes() {
        MongoDatabase database = client.getDatabase(config.getConnection().getDatabase());

        for (QueryConfig.IndexDefinition indexDef : config.getIndexes()) {
            createIndex(database, indexDef);
        }
    }

    private void createIndex(MongoDatabase database, QueryConfig.IndexDefinition indexDef) {
        MongoCollection<Document> collection = database.getCollection(indexDef.getCollection());

        try {
            IndexOptions options = new IndexOptions();
            options.name(indexDef.getName());

            if (indexDef.getOptions() != null) {
                if (indexDef.getOptions().containsKey("background")) {
                    options.background(indexDef.getOptions().getBoolean("background"));
                }
                if (indexDef.getOptions().containsKey("unique")) {
                    options.unique(indexDef.getOptions().getBoolean("unique"));
                }
                if (indexDef.getOptions().containsKey("sparse")) {
                    options.sparse(indexDef.getOptions().getBoolean("sparse"));
                }
            }

            log.info("Creating index {} on {}: {}", indexDef.getName(),
                indexDef.getCollection(), indexDef.getKeys().toJson());

            String result = collection.createIndex(indexDef.getKeys(), options);
            log.info("Index created: {}", result);

        } catch (Exception e) {
            // Index might already exist
            if (e.getMessage().contains("already exists")) {
                log.info("Index {} already exists on {}", indexDef.getName(), indexDef.getCollection());
            } else {
                log.error("Failed to create index {}: {}", indexDef.getName(), e.getMessage());
                throw e;
            }
        }
    }

    public void dropIndexes() {
        MongoDatabase database = client.getDatabase(config.getConnection().getDatabase());

        // Get unique collections from index definitions
        Set<String> collections = new HashSet<>();
        for (QueryConfig.IndexDefinition indexDef : config.getIndexes()) {
            collections.add(indexDef.getCollection());
        }

        for (String collectionName : collections) {
            try {
                MongoCollection<Document> collection = database.getCollection(collectionName);

                // Drop all non-_id indexes
                for (Document indexDoc : collection.listIndexes()) {
                    String indexName = indexDoc.getString("name");
                    if (!"_id_".equals(indexName)) {
                        log.info("Dropping index {} on {}", indexName, collectionName);
                        collection.dropIndex(indexName);
                    }
                }
            } catch (Exception e) {
                log.warn("Error dropping indexes on {}: {}", collectionName, e.getMessage());
            }
        }
    }

    public boolean verifyIndex(String collectionName, String indexName) {
        MongoDatabase database = client.getDatabase(config.getConnection().getDatabase());
        MongoCollection<Document> collection = database.getCollection(collectionName);

        for (Document indexDoc : collection.listIndexes()) {
            if (indexName.equals(indexDoc.getString("name"))) {
                return true;
            }
        }
        return false;
    }

    public void listIndexes(String collectionName) {
        MongoDatabase database = client.getDatabase(config.getConnection().getDatabase());
        MongoCollection<Document> collection = database.getCollection(collectionName);

        System.out.printf("Indexes on %s:%n", collectionName);
        for (Document indexDoc : collection.listIndexes()) {
            System.out.printf("  %s: %s%n",
                indexDoc.getString("name"),
                indexDoc.get("key", Document.class).toJson());
        }
    }
}
