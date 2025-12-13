package com.wf.benchmark.generator;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Generates address documents matching the sample schema.
 * Each document contains an array of addresses for a customer.
 */
public class AddressGenerator implements DataGenerator {

    private static final long BASE_CUSTOMER_NUMBER = 1_000_000_001L;

    private static final List<String> ADDRESS_USE_CODES = List.of(
        "CUSTOMER_RESIDENCE", "STATEMENT_ADDRESS", "BILLING_ADDRESS", "MAILING_ADDRESS"
    );

    private final RandomDataProvider random;
    private final int minAddresses;
    private final int maxAddresses;
    private final String collectionName;

    public AddressGenerator(RandomDataProvider random, int minAddresses, int maxAddresses, String collectionPrefix) {
        this.random = random;
        this.minAddresses = minAddresses;
        this.maxAddresses = maxAddresses;
        this.collectionName = collectionPrefix + "address";
    }

    @Override
    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public Document generate(long sequenceNumber) {
        long customerNumber = BASE_CUSTOMER_NUMBER + sequenceNumber;
        int customerCompanyNumber = random.randomInt(1, 3);

        Document doc = new Document();

        // _id (composite key matching identity)
        doc.append("_id", new Document()
            .append("customerNumber", customerNumber)
            .append("customerCompanyNumber", customerCompanyNumber));

        // addresses array
        List<Document> addresses = generateAddresses(customerNumber);
        doc.append("addresses", addresses);

        return doc;
    }

    private List<Document> generateAddresses(long customerNumber) {
        List<Document> addresses = new ArrayList<>();
        int count = random.randomInt(minAddresses, maxAddresses);

        // Ensure first address is always CUSTOMER_RESIDENCE
        List<String> usedCodes = new ArrayList<>();
        usedCodes.add("CUSTOMER_RESIDENCE");

        addresses.add(generateSingleAddress(customerNumber, "CUSTOMER_RESIDENCE", 1));

        // Add additional addresses with different use codes
        for (int i = 1; i < count; i++) {
            String useCode = ADDRESS_USE_CODES.stream()
                .filter(code -> !usedCodes.contains(code))
                .findFirst()
                .orElse(ADDRESS_USE_CODES.get(random.randomInt(0, ADDRESS_USE_CODES.size() - 1)));
            usedCodes.add(useCode);
            addresses.add(generateSingleAddress(customerNumber, useCode, i + 1));
        }

        return addresses;
    }

    private Document generateSingleAddress(long customerNumber, String addressUseCode, int occurrenceNumber) {
        String countryCode = random.country();
        String stateCode = random.stateForCountry(countryCode);
        String city = random.city();
        String postalCode = random.postalCode(countryCode, stateCode);
        Date lastMaintDate = random.recentTimestamp();
        Date createdTs = random.timestamp();
        Date updatedTs = random.recentTimestamp();

        // Build address lines
        List<String> addressLines = new ArrayList<>();

        // For business addresses, sometimes add company name line
        if ("BILLING_ADDRESS".equals(addressUseCode) && random.randomBoolean(0.3)) {
            addressLines.add(random.faker().company().name());
        }

        addressLines.add(random.streetAddress());

        String secondary = random.secondaryAddress();
        if (secondary != null) {
            addressLines.add(secondary);
        }

        // Build systemOfRecord addressKey
        String addressKey = String.format("00100%s%s%s%09d%05d%010d",
            countryCode,
            postalCode.replace(" ", "").replace("-", ""),
            stateCode,
            random.randomInt(1, 999999999),
            random.randomInt(1, 99999),
            customerNumber % 10000000000L);

        int cdcSeq = random.randomInt(30, 60);

        Document address = new Document()
            .append("addressUseCode", addressUseCode)
            .append("addressLines", addressLines)
            .append("cityName", city)
            .append("stateCode", stateCode)
            .append("postalCode", postalCode)
            .append("countryCode", countryCode)
            .append("lastMaintenanceDate", random.dateString(lastMaintDate))
            .append("systemOfRecord", new Document()
                .append("addressKey", addressKey)
                .append("addressUseOccurrenceNumber", occurrenceNumber)
                .append("addressTemporaryIndicator", "1")
                .append("effectiveDate", generateEffectiveDate())
                .append("expirationDate", "9999999"))
            .append("metaData", new Document()
                .append("createdByProcessName", "24201 - IL")
                .append("createdByIdentifier", "VT_INITIAL_LOAD")
                .append("createdTimestamp", createdTs)
                .append("updatedByProcessName", "24201 - CDC")
                .append("updatedByIdentifier", "VT_CDC_" + String.format("%03d", random.randomInt(1, 999)))
                .append("updatedTimestamp", updatedTs)
                .append("cdcDeletedFlag", "N")
                .append("ADDRESS", new Document()
                    .append("createdCdcIdentifier", random.cdcIdentifier(customerNumber, occurrenceNumber))
                    .append("createdCdcTimestamp", createdTs.getTime())
                    .append("updatedCdcIdentifier", random.cdcIdentifier(customerNumber, cdcSeq))
                    .append("updatedCdcTimestamp", updatedTs.getTime())));

        return address;
    }

    private String generateEffectiveDate() {
        // Format: YYMMDDD (7 digits) - based on sample data patterns
        int year = random.randomInt(19, 24);
        int month = random.randomInt(1, 12);
        int day = random.randomInt(1, 28);
        return String.format("%02d%02d%02d1", year, month, day);
    }
}
