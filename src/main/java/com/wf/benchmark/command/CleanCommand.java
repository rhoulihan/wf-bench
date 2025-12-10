package com.wf.benchmark.command;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.wf.benchmark.config.ConnectionConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "clean",
    description = "Clean test data from MongoDB collections",
    mixinStandardHelpOptions = true
)
public class CleanCommand implements Callable<Integer> {

    @Option(names = {"-c", "--connection-string"}, description = "MongoDB connection string", required = true)
    private String connectionString;

    @Option(names = {"-d", "--database"}, description = "Target database name", defaultValue = "benchmark")
    private String database;

    @Option(names = {"-P", "--collection-prefix"}, description = "Prefix for collection names", defaultValue = "")
    private String collectionPrefix;

    @Option(names = {"--drop-database"}, description = "Drop the entire database", defaultValue = "false")
    private boolean dropDatabase;

    @Option(names = {"-y", "--yes"}, description = "Skip confirmation prompt", defaultValue = "false")
    private boolean skipConfirmation;

    @Override
    public Integer call() {
        try {
            if (!skipConfirmation) {
                System.out.println("WARNING: This will delete data from the database.");
                System.out.printf("Database: %s%n", database);

                if (dropDatabase) {
                    System.out.println("Action: DROP ENTIRE DATABASE");
                } else {
                    System.out.printf("Collections: %sidentity, %saddress, %sphone%n",
                        collectionPrefix, collectionPrefix, collectionPrefix);
                }

                System.out.print("\nAre you sure? (yes/no): ");
                java.util.Scanner scanner = new java.util.Scanner(System.in);
                String response = scanner.nextLine().trim().toLowerCase();

                if (!response.equals("yes") && !response.equals("y")) {
                    System.out.println("Aborted.");
                    return 0;
                }
            }

            return executeClean();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int executeClean() {
        ConnectionConfig config = new ConnectionConfig();
        config.setConnectionString(connectionString);
        config.setDatabase(database);

        try (MongoClient client = config.createClient()) {
            MongoDatabase db = client.getDatabase(database);

            if (dropDatabase) {
                System.out.printf("Dropping database: %s%n", database);
                db.drop();
                System.out.println("Database dropped.");
            } else {
                List<String> collections = List.of(
                    collectionPrefix + "identity",
                    collectionPrefix + "address",
                    collectionPrefix + "phone"
                );

                for (String collName : collections) {
                    System.out.printf("Dropping collection: %s%n", collName);
                    db.getCollection(collName).drop();
                }

                System.out.println("Collections dropped.");
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error during clean: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
