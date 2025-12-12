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
 * Uses DETERMINISTIC sampling of specific customer numbers to ensure complete
 * bundles across all collections. Also generates MIXED MATCH test cases with:
 * - Complete matches (all search terms match same customer)
 * - Partial matches (some terms match, others don't)
 * - No matches (terms from different unrelated customers)
 *
 * Output format is a JSON file with sample values for each search parameter type.
 */
public class SampleDataCollector {

    private static final Logger log = LoggerFactory.getLogger(SampleDataCollector.class);

    // How many samples to collect per category for individual values
    private static final int SAMPLES_PER_CATEGORY = 10;

    // Number of target customers to sample deterministically
    // These will be sampled every N customers where N = totalCustomers / TARGET_SAMPLE_COUNT
    private static final int TARGET_SAMPLE_COUNT = 100;

    // Base customer number - must match IdentityGenerator.BASE_CUSTOMER_NUMBER
    private static final long BASE_CUSTOMER_NUMBER = 1_000_000_001L;

    // Sample collections for individual values (probabilistic)
    private final Map<String, List<String>> phoneNumbers = new ConcurrentHashMap<>();
    private final Map<String, List<String>> ssnLast4Values = new ConcurrentHashMap<>();
    private final Map<String, List<String>> accountNumbers = new ConcurrentHashMap<>();
    private final Map<String, List<String>> accountLast4Values = new ConcurrentHashMap<>();
    private final Map<String, List<String>> emails = new ConcurrentHashMap<>();
    private final Map<String, List<String>> cities = new ConcurrentHashMap<>();
    private final Map<String, List<String>> states = new ConcurrentHashMap<>();
    private final Map<String, List<String>> postalCodes = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> customerNumbers = new ConcurrentHashMap<>();

    // Deterministic customer bundles - indexed by customer number for guaranteed complete bundles
    private final Map<Long, CustomerDataBundle> bundlesByCustomer = new ConcurrentHashMap<>();

    // Set of target customer numbers to collect deterministically
    private final Set<Long> targetCustomers = ConcurrentHashMap.newKeySet();

    private final double sampleRate;
    private final Path outputPath;
    private final long totalCustomers;

    /**
     * Create a sample data collector.
     *
     * @param sampleRate Probability of collecting a sample (0.0-1.0), e.g., 0.001 = 0.1%
     * @param outputPath Path to write the sample data JSON file
     */
    public SampleDataCollector(double sampleRate, Path outputPath) {
        this(sampleRate, outputPath, 1_000_000); // Default to 1M customers
    }

    /**
     * Create a sample data collector with known total customer count.
     *
     * @param sampleRate Probability of collecting a sample (0.0-1.0)
     * @param outputPath Path to write the sample data JSON file
     * @param totalCustomers Total number of customers to be generated
     */
    public SampleDataCollector(double sampleRate, Path outputPath, long totalCustomers) {
        this.sampleRate = sampleRate;
        this.outputPath = outputPath;
        this.totalCustomers = totalCustomers;

        // Pre-compute target customer numbers for deterministic sampling
        // Sample every Nth customer to get TARGET_SAMPLE_COUNT samples spread across the data
        initializeTargetCustomers();
    }

    private void initializeTargetCustomers() {
        long interval = Math.max(1, totalCustomers / TARGET_SAMPLE_COUNT);
        for (int i = 0; i < TARGET_SAMPLE_COUNT; i++) {
            // Start from BASE_CUSTOMER_NUMBER and space evenly
            // sequenceNumber starts at 0, so first customer is BASE_CUSTOMER_NUMBER + 0 = BASE_CUSTOMER_NUMBER
            long sequenceOffset = i * interval;
            long customerNum = BASE_CUSTOMER_NUMBER + sequenceOffset;
            if (sequenceOffset < totalCustomers) {
                targetCustomers.add(customerNum);
                bundlesByCustomer.put(customerNum, new CustomerDataBundle(customerNum));
            }
        }
        log.info("Initialized {} target customers for deterministic sampling (interval: {}, first: {}, last: {})",
            targetCustomers.size(), interval,
            bundlesByCustomer.keySet().stream().min(Long::compare).orElse(0L),
            bundlesByCustomer.keySet().stream().max(Long::compare).orElse(0L));
    }

    /**
     * Check if we should collect this sample (probabilistic) for individual values.
     */
    public boolean shouldCollect() {
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    /**
     * Check if this customer is a target for deterministic sampling.
     */
    public boolean isTargetCustomer(Long customerNumber) {
        return customerNumber != null && targetCustomers.contains(customerNumber);
    }

    /**
     * Collect sample data from an identity document.
     */
    public void collectIdentitySample(Document doc) {
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

            // Probabilistic sampling for individual values
            if (shouldCollect()) {
                addSample(ssnLast4Values, "identity", ssnLast4);
                addSample(emails, "identity", email);
                addSample(customerNumbers, "identity", customerNumber);
            }

            // Deterministic sampling for complete bundles
            if (isTargetCustomer(customerNumber)) {
                CustomerDataBundle bundle = bundlesByCustomer.get(customerNumber);
                if (bundle != null) {
                    bundle.ssnLast4 = ssnLast4;
                    bundle.email = email;
                    bundle.fullName = fullName;
                    bundle.hasIdentity = true;
                }
            }

        } catch (Exception e) {
            log.debug("Error collecting identity sample: {}", e.getMessage());
        }
    }

    /**
     * Collect sample data from a phone document.
     */
    public void collectPhoneSample(Document doc) {
        try {
            Document phoneKey = doc.get("phoneKey", Document.class);
            if (phoneKey == null) return;

            Long customerNumber = phoneKey.getLong("customerNumber");
            String phoneNumber = phoneKey.getString("phoneNumber");

            // Probabilistic sampling
            if (shouldCollect()) {
                addSample(phoneNumbers, "phone", phoneNumber);
            }

            // Deterministic sampling for complete bundles
            if (isTargetCustomer(customerNumber) && phoneNumber != null) {
                CustomerDataBundle bundle = bundlesByCustomer.get(customerNumber);
                if (bundle != null && bundle.phoneNumber == null) {
                    bundle.phoneNumber = phoneNumber;
                    bundle.hasPhone = true;
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
        try {
            Document accountKey = doc.get("accountKey", Document.class);
            if (accountKey == null) return;

            String accountNumber = accountKey.getString("accountNumber");
            String accountLast4 = accountKey.getString("accountNumberLast4");

            // Probabilistic sampling
            if (shouldCollect()) {
                addSample(accountNumbers, "account", accountNumber);
                addSample(accountLast4Values, "account", accountLast4);
            }

            // Get primary customer number for deterministic sampling
            @SuppressWarnings("unchecked")
            List<Document> holders = doc.getList("accountHolders", Document.class);
            if (holders != null && !holders.isEmpty()) {
                Long customerNumber = holders.get(0).getLong("customerNumber");

                if (isTargetCustomer(customerNumber) && accountNumber != null) {
                    CustomerDataBundle bundle = bundlesByCustomer.get(customerNumber);
                    if (bundle != null && bundle.accountNumber == null) {
                        bundle.accountNumber = accountNumber;
                        bundle.accountLast4 = accountLast4;
                        bundle.hasAccount = true;
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

            // Probabilistic sampling
            if (shouldCollect()) {
                addSample(cities, "address", city);
                addSample(states, "address", state);
                addSample(postalCodes, "address", postalCode);
            }

            // Deterministic sampling for complete bundles
            if (isTargetCustomer(customerNumber)) {
                CustomerDataBundle bundle = bundlesByCustomer.get(customerNumber);
                if (bundle != null && bundle.city == null) {
                    bundle.city = city;
                    bundle.state = state;
                    bundle.postalCode = postalCode;
                    bundle.hasAddress = true;
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
        // Ensure parent directory exists (if there is one)
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

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

            // Customer bundles for UC queries - now with mixed matches
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
        // Get bundles from deterministic sampling
        List<CustomerDataBundle> completeBundles = bundlesByCustomer.values().stream()
            .filter(CustomerDataBundle::isComplete)
            .limit(20)
            .toList();

        // Get partial bundles (missing some data) for mixed match scenarios
        List<CustomerDataBundle> partialBundles = bundlesByCustomer.values().stream()
            .filter(b -> !b.isComplete() && b.hasIdentity)
            .limit(10)
            .toList();

        log.info("Writing UC test cases: {} complete bundles, {} partial bundles",
            completeBundles.size(), partialBundles.size());

        // UC-1: Phone + SSN Last 4
        writeUcCaseWithMixedMatches(writer, "uc1", "Phone + SSN Last 4",
            completeBundles, partialBundles,
            b -> b.phoneNumber != null && b.ssnLast4 != null,
            b -> Map.of("phone", b.phoneNumber, "ssnLast4", b.ssnLast4,
                "expectedMatch", "true", "customerNumber", String.valueOf(b.customerNumber)),
            false);

        // UC-2: Phone + SSN Last 4 + Account Last 4
        writeUcCaseWithMixedMatches(writer, "uc2", "Phone + SSN Last 4 + Account Last 4",
            completeBundles, partialBundles,
            b -> b.phoneNumber != null && b.ssnLast4 != null && b.accountLast4 != null,
            b -> Map.of("phone", b.phoneNumber, "ssnLast4", b.ssnLast4, "accountLast4", b.accountLast4,
                "expectedMatch", "true", "customerNumber", String.valueOf(b.customerNumber)),
            false);

        // UC-3: Phone + Account Last 4
        writeUcCaseWithMixedMatches(writer, "uc3", "Phone + Account Last 4",
            completeBundles, partialBundles,
            b -> b.phoneNumber != null && b.accountLast4 != null,
            b -> Map.of("phone", b.phoneNumber, "accountLast4", b.accountLast4,
                "expectedMatch", "true", "customerNumber", String.valueOf(b.customerNumber)),
            false);

        // UC-4: Account Number + SSN Last 4
        writeUcCaseWithMixedMatches(writer, "uc4", "Account Number + SSN Last 4",
            completeBundles, partialBundles,
            b -> b.accountNumber != null && b.ssnLast4 != null,
            b -> Map.of("accountNumber", b.accountNumber, "ssnLast4", b.ssnLast4,
                "expectedMatch", "true", "customerNumber", String.valueOf(b.customerNumber)),
            false);

        // UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
        writeUcCaseWithMixedMatches(writer, "uc5", "City/State/ZIP + SSN Last 4 + Account Last 4",
            completeBundles, partialBundles,
            b -> b.city != null && b.state != null && b.postalCode != null
                && b.ssnLast4 != null && b.accountLast4 != null,
            b -> {
                Map<String, String> map = new LinkedHashMap<>();
                map.put("city", b.city);
                map.put("state", b.state);
                map.put("zip", b.postalCode);
                map.put("ssnLast4", b.ssnLast4);
                map.put("accountLast4", b.accountLast4);
                map.put("expectedMatch", "true");
                map.put("customerNumber", String.valueOf(b.customerNumber));
                return map;
            },
            false);

        // UC-6: Email + Account Last 4
        writeUcCaseWithMixedMatches(writer, "uc6", "Email + Account Last 4",
            completeBundles, partialBundles,
            b -> b.email != null && b.accountLast4 != null,
            b -> Map.of("email", b.email, "accountLast4", b.accountLast4,
                "expectedMatch", "true", "customerNumber", String.valueOf(b.customerNumber)),
            false);

        // UC-7: Email + Phone + Account Number
        writeUcCaseWithMixedMatches(writer, "uc7", "Email + Phone + Account Number",
            completeBundles, partialBundles,
            b -> b.email != null && b.phoneNumber != null && b.accountNumber != null,
            b -> Map.of("email", b.email, "phone", b.phoneNumber, "accountNumber", b.accountNumber,
                "expectedMatch", "true", "customerNumber", String.valueOf(b.customerNumber)),
            true);
    }

    /**
     * Write UC test case with mixed match scenarios:
     * - Full matches (all terms from same customer)
     * - Partial matches (some terms match, swapped terms from different customer)
     * - No matches (all terms from different unrelated customers)
     */
    private void writeUcCaseWithMixedMatches(PrintWriter writer, String name, String description,
            List<CustomerDataBundle> completeBundles, List<CustomerDataBundle> partialBundles,
            java.util.function.Predicate<CustomerDataBundle> filter,
            java.util.function.Function<CustomerDataBundle, Map<String, String>> mapper,
            boolean isLast) {

        List<Map<String, String>> testCases = new ArrayList<>();

        // Add full matches (3 test cases where all terms match same customer)
        completeBundles.stream()
            .filter(filter)
            .limit(3)
            .forEach(b -> testCases.add(mapper.apply(b)));

        // Add partial/mixed matches (terms from different customers - should NOT match)
        List<CustomerDataBundle> matchingBundles = completeBundles.stream()
            .filter(filter)
            .toList();

        if (matchingBundles.size() >= 2) {
            // Create a mixed case: take first field from customer A, rest from customer B
            CustomerDataBundle a = matchingBundles.get(0);
            CustomerDataBundle b = matchingBundles.get(matchingBundles.size() - 1);

            Map<String, String> mixedCase = createMixedCase(name, a, b);
            if (mixedCase != null) {
                testCases.add(mixedCase);
            }
        }

        // Add no-match cases using fake/random values
        testCases.add(createNoMatchCase(name));

        writeUcCase(writer, name, description, testCases, isLast);
    }

    /**
     * Create a mixed case where some fields are from customer A and others from customer B.
     * This should result in partial matches but not a definitive match to either customer.
     */
    private Map<String, String> createMixedCase(String ucName, CustomerDataBundle a, CustomerDataBundle b) {
        Map<String, String> result = new LinkedHashMap<>();

        switch (ucName) {
            case "uc1" -> {
                result.put("phone", a.phoneNumber);       // From customer A
                result.put("ssnLast4", b.ssnLast4);       // From customer B
                result.put("expectedMatch", "partial");
                result.put("note", "phone from " + a.customerNumber + ", ssn from " + b.customerNumber);
            }
            case "uc2" -> {
                result.put("phone", a.phoneNumber);       // From A
                result.put("ssnLast4", b.ssnLast4);       // From B
                result.put("accountLast4", a.accountLast4); // From A
                result.put("expectedMatch", "partial");
                result.put("note", "mixed from customers " + a.customerNumber + " and " + b.customerNumber);
            }
            case "uc3" -> {
                result.put("phone", a.phoneNumber);       // From A
                result.put("accountLast4", b.accountLast4); // From B
                result.put("expectedMatch", "partial");
                result.put("note", "phone from " + a.customerNumber + ", account from " + b.customerNumber);
            }
            case "uc4" -> {
                result.put("accountNumber", a.accountNumber); // From A
                result.put("ssnLast4", b.ssnLast4);          // From B
                result.put("expectedMatch", "partial");
                result.put("note", "account from " + a.customerNumber + ", ssn from " + b.customerNumber);
            }
            case "uc5" -> {
                result.put("city", a.city);
                result.put("state", a.state);
                result.put("zip", a.postalCode);           // Address from A
                result.put("ssnLast4", b.ssnLast4);        // SSN from B
                result.put("accountLast4", b.accountLast4); // Account from B
                result.put("expectedMatch", "partial");
                result.put("note", "address from " + a.customerNumber + ", ssn/account from " + b.customerNumber);
            }
            case "uc6" -> {
                result.put("email", a.email);              // From A
                result.put("accountLast4", b.accountLast4); // From B
                result.put("expectedMatch", "partial");
                result.put("note", "email from " + a.customerNumber + ", account from " + b.customerNumber);
            }
            case "uc7" -> {
                result.put("email", a.email);              // From A
                result.put("phone", b.phoneNumber);        // From B
                result.put("accountNumber", a.accountNumber); // From A
                result.put("expectedMatch", "partial");
                result.put("note", "email/account from " + a.customerNumber + ", phone from " + b.customerNumber);
            }
            default -> { return null; }
        }

        return result;
    }

    /**
     * Create a test case with fake values that should NOT match any customer.
     */
    private Map<String, String> createNoMatchCase(String ucName) {
        Map<String, String> result = new LinkedHashMap<>();

        switch (ucName) {
            case "uc1" -> {
                result.put("phone", "9999999999");
                result.put("ssnLast4", "0000");
                result.put("expectedMatch", "none");
            }
            case "uc2" -> {
                result.put("phone", "9999999999");
                result.put("ssnLast4", "0000");
                result.put("accountLast4", "0000");
                result.put("expectedMatch", "none");
            }
            case "uc3" -> {
                result.put("phone", "9999999999");
                result.put("accountLast4", "0000");
                result.put("expectedMatch", "none");
            }
            case "uc4" -> {
                result.put("accountNumber", "0000000000000000");
                result.put("ssnLast4", "0000");
                result.put("expectedMatch", "none");
            }
            case "uc5" -> {
                result.put("city", "NONEXISTENT");
                result.put("state", "XX");
                result.put("zip", "00000");
                result.put("ssnLast4", "0000");
                result.put("accountLast4", "0000");
                result.put("expectedMatch", "none");
            }
            case "uc6" -> {
                result.put("email", "nonexistent@fake.invalid");
                result.put("accountLast4", "0000");
                result.put("expectedMatch", "none");
            }
            case "uc7" -> {
                result.put("email", "nonexistent@fake.invalid");
                result.put("phone", "9999999999");
                result.put("accountNumber", "0000000000000000");
                result.put("expectedMatch", "none");
            }
        }

        return result;
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
        final Long customerNumber;
        String ssnLast4;
        String email;
        String fullName;
        String phoneNumber;
        String accountNumber;
        String accountLast4;
        String city;
        String state;
        String postalCode;

        // Track which collections we have data from
        boolean hasIdentity;
        boolean hasPhone;
        boolean hasAccount;
        boolean hasAddress;

        CustomerDataBundle(Long customerNumber) {
            this.customerNumber = customerNumber;
        }

        boolean isComplete() {
            return hasIdentity && hasPhone && hasAccount && hasAddress
                && ssnLast4 != null && phoneNumber != null
                && accountNumber != null && accountLast4 != null;
        }

        @Override
        public String toString() {
            return String.format("Bundle[%d: identity=%b, phone=%b, account=%b, address=%b]",
                customerNumber, hasIdentity, hasPhone, hasAccount, hasAddress);
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

        long completeBundles = bundlesByCustomer.values().stream()
            .filter(CustomerDataBundle::isComplete).count();

        long partialBundles = bundlesByCustomer.values().stream()
            .filter(b -> !b.isComplete() && b.hasIdentity).count();

        return String.format("Total samples: %d, Complete bundles: %d, Partial bundles: %d",
            totalSamples, completeBundles, partialBundles);
    }
}
