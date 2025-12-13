package com.wf.benchmark.generator;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Generates address documents matching the sample schema.
 * Each document represents a single address with unique _id including addressKey.
 *
 * <p>Document structure:
 * <pre>
 * {
 *   "_id": {
 *     "customerNumber": 1000000001,
 *     "customerCompanyNumber": 1,
 *     "addressKey": "00100US12345CA..."
 *   },
 *   "addressUseCode": "CUSTOMER_RESIDENCE",
 *   "cityName": "San Francisco",
 *   "stateCode": "CA",
 *   "postalCode": "94102",
 *   ...
 * }
 * </pre>
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

    // Track current customer for multi-address generation
    private long currentCustomerNumber = -1;
    private int currentCustomerCompany = -1;
    private int currentAddressIndex = 0;
    private int addressesForCurrentCustomer = 0;
    private List<String> usedCodesForCustomer = new ArrayList<>();

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
        // Calculate which customer this address belongs to and which address index
        // We need to map sequenceNumber to (customerNumber, addressIndex)
        // This is complex because each customer has variable number of addresses

        // Simple approach: use sequenceNumber as unique address sequence
        // Customer number derived from sequence, but each address gets unique key
        long customerNumber = BASE_CUSTOMER_NUMBER + (sequenceNumber / 2); // ~2 addresses per customer avg
        int customerCompanyNumber = random.randomInt(1, 3);

        // Determine address use code (cycle through them)
        int addressIndex = (int)(sequenceNumber % ADDRESS_USE_CODES.size());
        String addressUseCode = ADDRESS_USE_CODES.get(addressIndex);

        return generateSingleAddressDocument(customerNumber, customerCompanyNumber, addressUseCode, addressIndex + 1);
    }

    /**
     * Generate a single address document with unique _id including addressKey.
     */
    private Document generateSingleAddressDocument(long customerNumber, int customerCompanyNumber,
                                                    String addressUseCode, int occurrenceNumber) {
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

        // Build unique addressKey for _id
        String addressKey = String.format("00100%s%s%s%09d%05d%010d",
            countryCode,
            postalCode.replace(" ", "").replace("-", ""),
            stateCode,
            random.randomInt(1, 999999999),
            random.randomInt(1, 99999),
            customerNumber % 10000000000L);

        int cdcSeq = random.randomInt(30, 60);

        // Build document with addressKey in _id for uniqueness
        Document doc = new Document();

        // _id includes addressKey for uniqueness
        doc.append("_id", new Document()
            .append("customerNumber", customerNumber)
            .append("customerCompanyNumber", customerCompanyNumber)
            .append("addressKey", addressKey));

        // Address fields at top level (not in array)
        doc.append("addressUseCode", addressUseCode)
            .append("addressLines", addressLines)
            .append("addressLine1", addressLines.isEmpty() ? "" : addressLines.get(0))
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

        return doc;
    }

    private String generateEffectiveDate() {
        // Format: YYMMDDD (7 digits) - based on sample data patterns
        int year = random.randomInt(19, 24);
        int month = random.randomInt(1, 12);
        int day = random.randomInt(1, 28);
        return String.format("%02d%02d%02d1", year, month, day);
    }
}
