package com.wf.benchmark.loader;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Creates MongoDB indexes on the benchmark collections.
 *
 * These indexes support the UC 1-7 search queries which use SQL/JDBC
 * to perform cross-collection joins with Oracle Text search.
 */
public class IndexCreator {

    private static final Logger log = LoggerFactory.getLogger(IndexCreator.class);

    private final MongoDatabase database;
    private final String collectionPrefix;

    public IndexCreator(MongoDatabase database, String collectionPrefix) {
        this.database = database;
        this.collectionPrefix = collectionPrefix != null ? collectionPrefix : "";
    }

    /**
     * Create all required indexes for benchmark queries.
     *
     * @param quiet if true, suppress console output
     */
    public void createAllIndexes(boolean quiet) {
        createIdentityIndexes(quiet);
        createPhoneIndexes(quiet);
        createAccountIndexes(quiet);
        createAddressIndexes(quiet);
        createJsonSearchIndex(quiet);
    }

    /**
     * Create JSON Search Indexes on all collections that use json_textcontains().
     * These indexes are required for the score() function used in UC queries.
     *
     * <p>Collections requiring search indexes:
     * <ul>
     *   <li>identity - for SSN and email searches</li>
     *   <li>phone - for phone number searches</li>
     *   <li>account - for account number searches</li>
     *   <li>address - for city name searches</li>
     * </ul>
     *
     * @param quiet if true, suppress console output
     */
    private void createJsonSearchIndex(boolean quiet) {
        // Create search indexes on all collections used by UC queries
        String[] collections = {"identity", "phone", "account", "address"};

        for (String baseName : collections) {
            String collName = collectionPrefix + baseName;
            String indexName = "idx_" + collName + "_search";

            if (!quiet) {
                System.out.printf("  Creating JSON Search Index %s.%s...%n", collName, indexName);
            }

            try {
                // Use MongoDB $sql aggregate operator to execute CREATE SEARCH INDEX DDL
                String ddl = String.format(
                    "CREATE SEARCH INDEX %s ON %s(DATA) FOR JSON",
                    indexName, collName.toUpperCase()
                );

                Document sqlCommand = new Document("$sql", ddl);
                database.getCollection(collName).aggregate(Arrays.asList(sqlCommand)).first();

                if (!quiet) {
                    System.out.printf("  JSON Search Index %s created successfully.%n", indexName);
                }
                log.info("Created JSON Search Index {} on {}", indexName, collName);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("already exists") || msg.contains("DRG-10700"))) {
                    // Index already exists - this is fine
                    if (!quiet) {
                        System.out.printf("  JSON Search Index %s already exists.%n", indexName);
                    }
                    log.debug("JSON Search Index {} already exists on {}", indexName, collName);
                } else {
                    log.warn("Failed to create JSON Search Index on {}: {}", collName, msg);
                    if (!quiet) {
                        System.out.printf("  Warning: Could not create JSON Search Index on %s: %s%n", collName, msg);
                    }
                }
            }
        }
    }

    private void createIdentityIndexes(boolean quiet) {
        String collName = collectionPrefix + "identity";
        MongoCollection<Document> coll = database.getCollection(collName);

        // Index on customerNumber for joins
        createIndex(coll, collName, "_id.customerNumber", quiet);

        // Index on SSN last 4 for UC-1, UC-2, UC-4, UC-5
        createIndex(coll, collName, "common.taxIdentificationNumberLast4", quiet);

        // Index on email for UC-6, UC-7 (emails array)
        createIndex(coll, collName, "emails.emailAddress", quiet);

        // Index on full name for potential fuzzy search
        createIndex(coll, collName, "common.fullName", quiet);
    }

    private void createPhoneIndexes(boolean quiet) {
        String collName = collectionPrefix + "phone";
        MongoCollection<Document> coll = database.getCollection(collName);

        // Index on customerNumber for joins
        createIndex(coll, collName, "phoneKey.customerNumber", quiet);

        // Index on phone number for UC-1, UC-2, UC-3, UC-7
        createIndex(coll, collName, "phoneKey.phoneNumber", quiet);
    }

    private void createAccountIndexes(boolean quiet) {
        String collName = collectionPrefix + "account";
        MongoCollection<Document> coll = database.getCollection(collName);

        // Index on account number for UC-4, UC-7
        createIndex(coll, collName, "accountKey.accountNumber", quiet);

        // Index on account last 4 for UC-2, UC-3, UC-5, UC-6
        createIndex(coll, collName, "accountKey.accountNumberLast4", quiet);

        // Index on account holder customer number for joins
        createIndex(coll, collName, "accountHolders.customerNumber", quiet);
    }

    private void createAddressIndexes(boolean quiet) {
        String collName = collectionPrefix + "address";
        MongoCollection<Document> coll = database.getCollection(collName);

        // Index on customerNumber for joins
        createIndex(coll, collName, "_id.customerNumber", quiet);

        // Compound index for city/state/zip search (UC-5)
        createCompoundIndex(coll, collName,
            List.of("addresses.cityName", "addresses.stateCode", "addresses.postalCode"), quiet);

        // Individual indexes for partial matches
        createIndex(coll, collName, "addresses.cityName", quiet);
        createIndex(coll, collName, "addresses.stateCode", quiet);
        createIndex(coll, collName, "addresses.postalCode", quiet);
    }

    private void createIndex(MongoCollection<Document> coll, String collName, String field, boolean quiet) {
        try {
            String indexName = field.replace(".", "_") + "_1";
            if (!quiet) {
                System.out.printf("  Creating index %s.%s...%n", collName, indexName);
            }
            coll.createIndex(new Document(field, 1), new IndexOptions().name(indexName).background(true));
            log.debug("Created index {} on {}", indexName, collName);
        } catch (Exception e) {
            // Index might already exist
            if (!e.getMessage().contains("already exists")) {
                log.warn("Failed to create index on {}.{}: {}", collName, field, e.getMessage());
                if (!quiet) {
                    System.out.printf("  Warning: Could not create index on %s: %s%n", field, e.getMessage());
                }
            }
        }
    }

    private void createCompoundIndex(MongoCollection<Document> coll, String collName,
                                      List<String> fields, boolean quiet) {
        try {
            Document indexSpec = new Document();
            StringBuilder nameBuilder = new StringBuilder();
            for (String field : fields) {
                indexSpec.append(field, 1);
                if (nameBuilder.length() > 0) {
                    nameBuilder.append("_");
                }
                nameBuilder.append(field.replace(".", "_")).append("_1");
            }
            String indexName = nameBuilder.toString();

            if (!quiet) {
                System.out.printf("  Creating compound index %s.%s...%n", collName, indexName);
            }
            coll.createIndex(indexSpec, new IndexOptions().name(indexName).background(true));
            log.debug("Created compound index {} on {}", indexName, collName);
        } catch (Exception e) {
            if (!e.getMessage().contains("already exists")) {
                log.warn("Failed to create compound index on {}: {}", collName, e.getMessage());
                if (!quiet) {
                    System.out.printf("  Warning: Could not create compound index: %s%n", e.getMessage());
                }
            }
        }
    }
}
