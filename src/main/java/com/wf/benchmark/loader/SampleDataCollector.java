package com.wf.benchmark.loader;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Collects sample data during data generation for use in benchmark tests.
 *
 * Sample data is collected at random intervals during generation to ensure
 * realistic test data that will actually match documents in the database.
 *
 * Output format is a JSON file with sample values for each search parameter type.
 */
public class SampleDataCollector {

    private static final Logger log = LoggerFactory.getLogger(SampleDataCollector.class);

    // How many samples to collect per category
    private static final int SAMPLES_PER_CATEGORY = 10;

    // Sample collections
    private final Map<String, List<String>> phoneNumbers = new ConcurrentHashMap<>();
    private final Map<String, List<String>> ssnLast4Values = new ConcurrentHashMap<>();
    private final Map<String, List<String>> accountNumbers = new ConcurrentHashMap<>();
    private final Map<String, List<String>> accountLast4Values = new ConcurrentHashMap<>();
    private final Map<String, List<String>> emails = new ConcurrentHashMap<>();
    private final Map<String, List<String>> cities = new ConcurrentHashMap<>();
    private final Map<String, List<String>> states = new ConcurrentHashMap<>();
    private final Map<String, List<String>> postalCodes = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> customerNumbers = new ConcurrentHashMap<>();

    // Track customer-to-data relationships for UC queries that need multiple matching fields
    private final List<CustomerDataBundle> customerBundles = Collections.synchronizedList(new ArrayList<>());

    private final double sampleRate;
    private final Path outputPath;

    /**
     * Create a sample data collector.
     *
     * @param sampleRate Probability of collecting a sample (0.0-1.0), e.g., 0.001 = 0.1%
     * @param outputPath Path to write the sample data JSON file
     */
    public SampleDataCollector(double sampleRate, Path outputPath) {
        this.sampleRate = sampleRate;
        this.outputPath = outputPath;
    }

    /**
     * Check if we should collect this sample (probabilistic).
     */
    public boolean shouldCollect() {
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    /**
     * Collect sample data from an identity document.
     */
    public void collectIdentitySample(Document doc) {
        if (!shouldCollect()) return;

        try {
            Document id = doc.get("_id", Document.class);
            Document common = doc.get("common", Document.class);

            if (id == null || common == null) return;

            Long customerNumber = id.getLong("customerNumber");
            String ssnLast4 = common.getString("taxIdentificationNumberLast4");
            String fullName = common.getString("fullName");

            // Get email from embedded emails array
            String email = null;
            @SuppressWarnings("unchecked")
            List<Document> emailList = doc.getList("emails", Document.class);
            if (emailList != null && !emailList.isEmpty()) {
                email = emailList.get(0).getString("emailAddress");
            }

            // Store individual samples
            addSample(ssnLast4Values, "identity", ssnLast4);
            addSample(emails, "identity", email);
            addSample(customerNumbers, "identity", customerNumber);

            // Create a bundle for this customer
            if (customerNumber != null && ssnLast4 != null) {
                CustomerDataBundle bundle = new CustomerDataBundle();
                bundle.customerNumber = customerNumber;
                bundle.ssnLast4 = ssnLast4;
                bundle.email = email;
                bundle.fullName = fullName;
                customerBundles.add(bundle);
            }

        } catch (Exception e) {
            log.debug("Error collecting identity sample: {}", e.getMessage());
        }
    }

    /**
     * Collect sample data from a phone document.
     */
    public void collectPhoneSample(Document doc) {
        if (!shouldCollect()) return;

        try {
            Document phoneKey = doc.get("phoneKey", Document.class);
            if (phoneKey == null) return;

            Long customerNumber = phoneKey.getLong("customerNumber");
            String phoneNumber = phoneKey.getString("phoneNumber");

            addSample(phoneNumbers, "phone", phoneNumber);

            // Try to associate with existing customer bundle
            if (customerNumber != null && phoneNumber != null) {
                for (CustomerDataBundle bundle : customerBundles) {
                    if (bundle.customerNumber.equals(customerNumber)) {
                        if (bundle.phoneNumber == null) {
                            bundle.phoneNumber = phoneNumber;
                        }
                        break;
                    }
                }
            }

        } catch (Exception e) {
            log.debug("Error collecting phone sample: {}", e.getMessage());
        }
    }

    /**
     * Collect sample data from an account document.
     */
    public void collectAccountSample(Document doc) {
        if (!shouldCollect()) return;

        try {
            Document accountKey = doc.get("accountKey", Document.class);
            if (accountKey == null) return;

            String accountNumber = accountKey.getString("accountNumber");
            String accountLast4 = accountKey.getString("accountNumberLast4");

            addSample(accountNumbers, "account", accountNumber);
            addSample(accountLast4Values, "account", accountLast4);

            // Get primary customer number
            @SuppressWarnings("unchecked")
            List<Document> holders = doc.getList("accountHolders", Document.class);
            if (holders != null && !holders.isEmpty()) {
                Long customerNumber = holders.get(0).getLong("customerNumber");

                // Try to associate with existing customer bundle
                if (customerNumber != null && accountNumber != null) {
                    for (CustomerDataBundle bundle : customerBundles) {
                        if (bundle.customerNumber.equals(customerNumber)) {
                            if (bundle.accountNumber == null) {
                                bundle.accountNumber = accountNumber;
                                bundle.accountLast4 = accountLast4;
                            }
                            break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.debug("Error collecting account sample: {}", e.getMessage());
        }
    }

    /**
     * Collect sample data from an address document.
     */
    public void collectAddressSample(Document doc) {
        if (!shouldCollect()) return;

        try {
            Document id = doc.get("_id", Document.class);
            @SuppressWarnings("unchecked")
            List<Document> addresses = doc.getList("addresses", Document.class);

            if (id == null || addresses == null || addresses.isEmpty()) return;

            Long customerNumber = id.getLong("customerNumber");
            Document firstAddress = addresses.get(0);

            String city = firstAddress.getString("cityName");
            String state = firstAddress.getString("stateCode");
            String postalCode = firstAddress.getString("postalCode");

            addSample(cities, "address", city);
            addSample(states, "address", state);
            addSample(postalCodes, "address", postalCode);

            // Try to associate with existing customer bundle
            if (customerNumber != null) {
                for (CustomerDataBundle bundle : customerBundles) {
                    if (bundle.customerNumber.equals(customerNumber)) {
                        if (bundle.city == null) {
                            bundle.city = city;
                            bundle.state = state;
                            bundle.postalCode = postalCode;
                        }
                        break;
                    }
                }
            }

        } catch (Exception e) {
            log.debug("Error collecting address sample: {}", e.getMessage());
        }
    }

    private <T> void addSample(Map<String, List<T>> map, String category, T value) {
        if (value == null) return;
        map.computeIfAbsent(category, k -> Collections.synchronizedList(new ArrayList<>()));
        List<T> list = map.get(category);
        if (list.size() < SAMPLES_PER_CATEGORY) {
            list.add(value);
        }
    }

    /**
     * Write collected sample data to the output file.
     */
    public void writeToFile() throws IOException {
        // Ensure parent directory exists
        Files.createDirectories(outputPath.getParent());

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath.toFile()))) {
            writer.println("{");

            // Individual sample values
            writer.println("  \"samples\": {");
            writeSampleList(writer, "phoneNumbers", flattenValues(phoneNumbers), false);
            writeSampleList(writer, "ssnLast4Values", flattenValues(ssnLast4Values), false);
            writeSampleList(writer, "accountNumbers", flattenValues(accountNumbers), false);
            writeSampleList(writer, "accountLast4Values", flattenValues(accountLast4Values), false);
            writeSampleList(writer, "emails", flattenValues(emails), false);
            writeSampleList(writer, "cities", flattenValues(cities), false);
            writeSampleList(writer, "states", flattenValues(states), false);
            writeSampleList(writer, "postalCodes", flattenValues(postalCodes), true);
            writer.println("  },");

            // Customer bundles for UC queries
            writer.println("  \"ucTestCases\": {");
            writeUcTestCases(writer);
            writer.println("  }");

            writer.println("}");
        }

        log.info("Sample data written to: {}", outputPath);
    }

    private List<String> flattenValues(Map<String, ?> map) {
        List<String> result = new ArrayList<>();
        for (Object value : map.values()) {
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        result.add(String.valueOf(item));
                    }
                }
            }
        }
        return result;
    }

    private void writeSampleList(PrintWriter writer, String name, List<String> values, boolean isLast) {
        writer.print("    \"" + name + "\": [");
        StringJoiner joiner = new StringJoiner(", ");
        for (String v : values) {
            joiner.add("\"" + escapeJson(v) + "\"");
        }
        writer.print(joiner.toString());
        writer.println("]" + (isLast ? "" : ","));
    }

    private void writeUcTestCases(PrintWriter writer) {
        // Find complete bundles for each UC case
        List<CustomerDataBundle> completeBundles = customerBundles.stream()
            .filter(b -> b.isComplete())
            .limit(5)
            .toList();

        // UC-1: Phone + SSN Last 4
        writeUcCase(writer, "uc1", "Phone + SSN Last 4",
            completeBundles.stream()
                .filter(b -> b.phoneNumber != null && b.ssnLast4 != null)
                .map(b -> Map.of("phone", b.phoneNumber, "ssnLast4", b.ssnLast4))
                .limit(3)
                .toList(), false);

        // UC-2: Phone + SSN Last 4 + Account Last 4
        writeUcCase(writer, "uc2", "Phone + SSN Last 4 + Account Last 4",
            completeBundles.stream()
                .filter(b -> b.phoneNumber != null && b.ssnLast4 != null && b.accountLast4 != null)
                .map(b -> Map.of("phone", b.phoneNumber, "ssnLast4", b.ssnLast4, "accountLast4", b.accountLast4))
                .limit(3)
                .toList(), false);

        // UC-3: Phone + Account Last 4
        writeUcCase(writer, "uc3", "Phone + Account Last 4",
            completeBundles.stream()
                .filter(b -> b.phoneNumber != null && b.accountLast4 != null)
                .map(b -> Map.of("phone", b.phoneNumber, "accountLast4", b.accountLast4))
                .limit(3)
                .toList(), false);

        // UC-4: Account Number + SSN Last 4
        writeUcCase(writer, "uc4", "Account Number + SSN Last 4",
            completeBundles.stream()
                .filter(b -> b.accountNumber != null && b.ssnLast4 != null)
                .map(b -> Map.of("accountNumber", b.accountNumber, "ssnLast4", b.ssnLast4))
                .limit(3)
                .toList(), false);

        // UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
        writeUcCase(writer, "uc5", "City/State/ZIP + SSN Last 4 + Account Last 4",
            completeBundles.stream()
                .filter(b -> b.city != null && b.state != null && b.postalCode != null
                    && b.ssnLast4 != null && b.accountLast4 != null)
                .map(b -> Map.of("city", b.city, "state", b.state, "zip", b.postalCode,
                    "ssnLast4", b.ssnLast4, "accountLast4", b.accountLast4))
                .limit(3)
                .toList(), false);

        // UC-6: Email + Account Last 4
        writeUcCase(writer, "uc6", "Email + Account Last 4",
            completeBundles.stream()
                .filter(b -> b.email != null && b.accountLast4 != null)
                .map(b -> Map.of("email", b.email, "accountLast4", b.accountLast4))
                .limit(3)
                .toList(), false);

        // UC-7: Email + Phone + Account Number
        writeUcCase(writer, "uc7", "Email + Phone + Account Number",
            completeBundles.stream()
                .filter(b -> b.email != null && b.phoneNumber != null && b.accountNumber != null)
                .map(b -> Map.of("email", b.email, "phone", b.phoneNumber, "accountNumber", b.accountNumber))
                .limit(3)
                .toList(), true);
    }

    private void writeUcCase(PrintWriter writer, String name, String description,
                             List<Map<String, String>> testCases, boolean isLast) {
        writer.println("    \"" + name + "\": {");
        writer.println("      \"description\": \"" + description + "\",");
        writer.println("      \"testCases\": [");

        for (int i = 0; i < testCases.size(); i++) {
            Map<String, String> tc = testCases.get(i);
            writer.print("        {");
            StringJoiner joiner = new StringJoiner(", ");
            for (Map.Entry<String, String> entry : tc.entrySet()) {
                joiner.add("\"" + entry.getKey() + "\": \"" + escapeJson(entry.getValue()) + "\"");
            }
            writer.print(joiner.toString());
            writer.println("}" + (i < testCases.size() - 1 ? "," : ""));
        }

        writer.println("      ]");
        writer.println("    }" + (isLast ? "" : ","));
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Bundle of customer data for UC test cases.
     * A complete bundle has data from all collections for a single customer.
     */
    private static class CustomerDataBundle {
        Long customerNumber;
        String ssnLast4;
        String email;
        String fullName;
        String phoneNumber;
        String accountNumber;
        String accountLast4;
        String city;
        String state;
        String postalCode;

        boolean isComplete() {
            return customerNumber != null && ssnLast4 != null && phoneNumber != null
                && accountNumber != null && accountLast4 != null;
        }
    }

    /**
     * Get statistics about collected samples.
     */
    public String getStats() {
        int totalSamples = phoneNumbers.values().stream().mapToInt(List::size).sum()
            + ssnLast4Values.values().stream().mapToInt(List::size).sum()
            + accountNumbers.values().stream().mapToInt(List::size).sum()
            + emails.values().stream().mapToInt(List::size).sum()
            + cities.values().stream().mapToInt(List::size).sum();

        long completeBundles = customerBundles.stream().filter(CustomerDataBundle::isComplete).count();

        return String.format("Total samples: %d, Complete customer bundles: %d",
            totalSamples, completeBundles);
    }
}
