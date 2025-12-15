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
 *
 * <p>JSON Search Indexes are created with wildcard optimization (per Rodrigo Fuentes):
 * <ul>
 *   <li>WILDCARD_INDEX=TRUE - enables k-gram index for efficient wildcard searches</li>
 *   <li>WILDCARD_INDEX_K=4 - 4-character gram index</li>
 * </ul>
 * This optimizes `%term` patterns used in SSN last-4 searches.
 */
public class IndexCreator {

    private static final Logger log = LoggerFactory.getLogger(IndexCreator.class);

    /** Wordlist preference name for wildcard-optimized indexes */
    private static final String WORDLIST_PREFERENCE = "idx_wl";

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
        createWordlistPreference(quiet);
        createJsonSearchIndex(quiet);
    }

    /**
     * Create the wildcard wordlist preference for optimized text search.
     * This preference enables k-gram indexing for efficient wildcard pattern searches.
     *
     * @param quiet if true, suppress console output
     */
    private void createWordlistPreference(boolean quiet) {
        if (!quiet) {
            System.out.println("  Creating wildcard wordlist preference...");
        }

        try {
            // Try to create the wordlist preference using PL/SQL via $sql
            // Note: This may fail if the preference already exists or if $sql doesn't support PL/SQL
            String plsql = String.format(
                "BEGIN " +
                "  BEGIN ctx_ddl.drop_preference('%s'); EXCEPTION WHEN OTHERS THEN NULL; END; " +
                "  ctx_ddl.create_preference('%s', 'BASIC_WORDLIST'); " +
                "  ctx_ddl.set_attribute('%s', 'WILDCARD_INDEX', 'TRUE'); " +
                "  ctx_ddl.set_attribute('%s', 'WILDCARD_INDEX_K', '4'); " +
                "END;",
                WORDLIST_PREFERENCE, WORDLIST_PREFERENCE, WORDLIST_PREFERENCE, WORDLIST_PREFERENCE
            );

            Document sqlCommand = new Document("$sql", plsql);
            database.getCollection("identity").aggregate(Arrays.asList(sqlCommand)).first();

            if (!quiet) {
                System.out.printf("  Wordlist preference '%s' created (WILDCARD_INDEX=TRUE, K=4).%n", WORDLIST_PREFERENCE);
            }
            log.info("Created wordlist preference {} with WILDCARD_INDEX=TRUE, K=4", WORDLIST_PREFERENCE);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("already exists") || msg.contains("DRG-10700"))) {
                if (!quiet) {
                    System.out.printf("  Wordlist preference '%s' already exists.%n", WORDLIST_PREFERENCE);
                }
                log.debug("Wordlist preference {} already exists", WORDLIST_PREFERENCE);
            } else {
                // PL/SQL via $sql may not be supported - this is expected
                log.warn("Could not create wordlist preference via MongoDB $sql (may need manual creation): {}", msg);
                if (!quiet) {
                    System.out.printf("  Note: Wordlist preference may need manual creation via SQL*Plus.%n");
                    System.out.printf("  Run: BEGIN ctx_ddl.create_preference('%s', 'BASIC_WORDLIST'); " +
                        "ctx_ddl.set_attribute('%s', 'WILDCARD_INDEX', 'TRUE'); " +
                        "ctx_ddl.set_attribute('%s', 'WILDCARD_INDEX_K', '4'); END;%n",
                        WORDLIST_PREFERENCE, WORDLIST_PREFERENCE, WORDLIST_PREFERENCE);
                }
            }
        }
    }

    /**
     * Create JSON Search Indexes on all collections that use json_textcontains().
     * These indexes are required for the score() function used in UC queries.
     *
     * <p>Indexes are created with the wildcard wordlist preference for optimized
     * `%term` pattern searches (SSN last-4, etc).
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
                System.out.printf("  Creating JSON Search Index %s.%s (with wildcard wordlist)...%n", collName, indexName);
            }

            try {
                // Use MongoDB $sql aggregate operator to execute CREATE SEARCH INDEX DDL
                // Include wordlist parameter for wildcard optimization
                String ddl = String.format(
                    "CREATE SEARCH INDEX %s ON %s(DATA) FOR JSON PARAMETERS ('wordlist %s')",
                    indexName, collName.toUpperCase(), WORDLIST_PREFERENCE
                );

                Document sqlCommand = new Document("$sql", ddl);
                database.getCollection(collName).aggregate(Arrays.asList(sqlCommand)).first();

                if (!quiet) {
                    System.out.printf("  JSON Search Index %s created successfully (wildcard optimized).%n", indexName);
                }
                log.info("Created JSON Search Index {} on {} with wordlist {}", indexName, collName, WORDLIST_PREFERENCE);
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
