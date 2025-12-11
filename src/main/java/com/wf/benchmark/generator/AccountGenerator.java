package com.wf.benchmark.generator;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Generates account documents with support for multiple account holders.
 *
 * Key Design:
 * - Each account can have 1-4 account holders (joint accounts, authorized users)
 * - Account holders reference identity documents via customerNumber
 * - Distribution: 60% single, 25% joint (2), 10% joint (3+), 5% authorized users
 */
public class AccountGenerator implements DataGenerator {

    private static final long BASE_CUSTOMER_NUMBER = 1_000_000_001L;

    private static final List<String> PRODUCT_TYPE_CODES = List.of(
        "CHECKING", "SAVINGS", "MONEY_MARKET", "CD", "IRA",
        "BROKERAGE", "CREDIT_CARD", "LOAN", "MORTGAGE", "LINE_OF_CREDIT"
    );

    private static final List<String> ACCOUNT_STATUS_CODES = List.of(
        "ACTIVE", "INACTIVE", "DORMANT", "CLOSED", "PENDING"
    );

    private static final List<String> RELATIONSHIP_TYPES = List.of(
        "PRIMARY", "JOINT", "AUTHORIZED", "BENEFICIARY", "CUSTODIAN"
    );

    private static final List<String> COMPANY_OF_INTEREST_IDS = List.of(
        "WF_BANK", "WF_ADVISOR", "WF_BROKERAGE", "WF_MORTGAGE", "WF_CREDIT"
    );

    private final RandomDataProvider random;
    private final double accountsPerIdentity;
    private final String collectionName;
    private final long maxIdentityCount;

    public AccountGenerator(RandomDataProvider random, double accountsPerIdentity,
                           String collectionPrefix, long maxIdentityCount) {
        this.random = random;
        this.accountsPerIdentity = accountsPerIdentity;
        this.collectionName = collectionPrefix + "account";
        this.maxIdentityCount = maxIdentityCount;
    }

    @Override
    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public Document generate(long sequenceNumber) {
        // Generate account number
        String accountNumber = generateAccountNumber(sequenceNumber);
        String accountNumberLast4 = accountNumber.substring(accountNumber.length() - 4);

        // Determine product type based on sequence for variety
        String productTypeCode = PRODUCT_TYPE_CODES.get((int) (sequenceNumber % PRODUCT_TYPE_CODES.size()));

        // Map sequence to primary customer
        long primaryCustomerOffset = (long) (sequenceNumber / accountsPerIdentity);
        long primaryCustomerNumber = BASE_CUSTOMER_NUMBER + primaryCustomerOffset;

        // Generate account holders
        List<Document> accountHolders = generateAccountHolders(primaryCustomerNumber);

        // Determine account status (95% active)
        String accountStatus = random.randomBoolean(0.95) ? "ACTIVE" :
            random.randomChoice(ACCOUNT_STATUS_CODES.subList(1, ACCOUNT_STATUS_CODES.size()));

        String coid = random.randomChoice(COMPANY_OF_INTEREST_IDS);

        Date createdTs = random.timestamp();
        Date updatedTs = random.recentTimestamp();

        // Build the account document
        Document doc = new Document()
            .append("_id", new ObjectId())
            .append("accountKey", new Document()
                .append("accountNumber", accountNumber)
                .append("accountNumberLast4", accountNumberLast4)
                .append("accountNumberTokenized", generateTokenizedAccount(accountNumber)))
            .append("productTypeCode", productTypeCode)
            .append("companyOfInterestId", coid)
            .append("accountStatus", accountStatus)
            .append("accountHolders", accountHolders)
            .append("openDate", random.dateString(createdTs))
            .append("balanceAmount", generateBalanceAmount(productTypeCode))
            .append("interestRate", generateInterestRate(productTypeCode))
            .append("metaData", new Document()
                .append("createdByProcessName", "24201 - IL")
                .append("createdByIdentifier", "VT_INITIAL_LOAD")
                .append("createdTimestamp", createdTs)
                .append("updatedByProcessName", "24201 - CDC")
                .append("updatedByIdentifier", "VT_CDC")
                .append("updatedTimestamp", updatedTs)
                .append("cdcDeletedFlag", "N")
                .append("ACCOUNT", new Document()
                    .append("createdCdcIdentifier", "CDC_ACC_" + sequenceNumber + "_001")
                    .append("createdCdcTimestamp", createdTs.getTime())
                    .append("updatedCdcIdentifier", "CDC_ACC_" + sequenceNumber + "_" +
                        String.format("%03d", random.randomInt(10, 99)))
                    .append("updatedCdcTimestamp", updatedTs.getTime())));

        return doc;
    }

    /**
     * Generate account holders array with realistic distribution.
     * 60% single holder, 25% joint (2), 10% joint (3+), 5% authorized users
     */
    private List<Document> generateAccountHolders(long primaryCustomerNumber) {
        List<Document> holders = new ArrayList<>();

        // Determine holder count based on distribution
        double rand = random.randomDouble();
        int holderCount;
        if (rand < 0.60) {
            holderCount = 1;  // Single holder
        } else if (rand < 0.85) {
            holderCount = 2;  // Joint account (2 holders)
        } else if (rand < 0.95) {
            holderCount = random.randomInt(3, 4);  // Joint (3-4 holders)
        } else {
            holderCount = random.randomInt(2, 3);  // With authorized users
        }

        // Primary holder
        holders.add(new Document()
            .append("customerNumber", primaryCustomerNumber)
            .append("customerCompanyNumber", random.randomInt(1, 3))
            .append("relationshipType", "PRIMARY")
            .append("isPrimaryHolder", true)
            .append("addedDate", random.dateString(random.timestamp())));

        // Additional holders
        for (int i = 1; i < holderCount; i++) {
            // Pick a related customer (nearby customer number for realism)
            long relatedCustomerNumber = getRelatedCustomerNumber(primaryCustomerNumber, i);
            String relationshipType = determineRelationshipType(i, rand >= 0.95);

            holders.add(new Document()
                .append("customerNumber", relatedCustomerNumber)
                .append("customerCompanyNumber", random.randomInt(1, 3))
                .append("relationshipType", relationshipType)
                .append("isPrimaryHolder", false)
                .append("addedDate", random.dateString(random.recentTimestamp())));
        }

        return holders;
    }

    /**
     * Get a related customer number for joint accounts.
     * Uses nearby customer numbers to simulate family/business relationships.
     */
    private long getRelatedCustomerNumber(long primaryCustomerNumber, int index) {
        // For joint accounts, pick customers that are numerically close (simulating family)
        // But ensure we don't go below base or above max
        long offset = random.randomInt(1, 100) * (random.randomBoolean() ? 1 : -1);
        long relatedNumber = primaryCustomerNumber + offset;

        // Ensure within bounds
        if (relatedNumber < BASE_CUSTOMER_NUMBER) {
            relatedNumber = primaryCustomerNumber + Math.abs(offset);
        }
        if (relatedNumber >= BASE_CUSTOMER_NUMBER + maxIdentityCount) {
            relatedNumber = primaryCustomerNumber - Math.abs(offset);
        }

        // Ensure not same as primary
        if (relatedNumber == primaryCustomerNumber) {
            relatedNumber = primaryCustomerNumber + index;
        }

        return relatedNumber;
    }

    private String determineRelationshipType(int index, boolean isAuthorizedUser) {
        if (isAuthorizedUser && index > 0) {
            return random.randomBoolean(0.7) ? "AUTHORIZED" : "BENEFICIARY";
        }
        return "JOINT";
    }

    private String generateAccountNumber(long sequenceNumber) {
        // 12-digit account number
        return String.format("%012d", 100000000000L + sequenceNumber);
    }

    private String generateTokenizedAccount(String accountNumber) {
        // Simple tokenization simulation
        return "TOK" + accountNumber.hashCode() + random.randomInt(1000, 9999);
    }

    private Double generateBalanceAmount(String productType) {
        if (!random.randomBoolean(0.85)) {
            return null;  // 15% have no balance (new accounts, etc.)
        }

        return switch (productType) {
            case "CHECKING" -> (double) random.randomInt(100, 50000);
            case "SAVINGS" -> (double) random.randomInt(500, 100000);
            case "MONEY_MARKET" -> (double) random.randomInt(10000, 500000);
            case "CD" -> (double) random.randomInt(1000, 250000);
            case "IRA" -> (double) random.randomInt(5000, 1000000);
            case "BROKERAGE" -> (double) random.randomInt(10000, 5000000);
            case "CREDIT_CARD" -> (double) random.randomInt(0, 25000);
            case "LOAN" -> (double) random.randomInt(5000, 50000);
            case "MORTGAGE" -> (double) random.randomInt(100000, 1000000);
            case "LINE_OF_CREDIT" -> (double) random.randomInt(0, 100000);
            default -> (double) random.randomInt(100, 10000);
        };
    }

    private Double generateInterestRate(String productType) {
        return switch (productType) {
            case "CHECKING" -> random.randomBoolean(0.3) ? 0.01 + (random.randomDouble() * 0.5) : null;
            case "SAVINGS" -> 0.5 + (random.randomDouble() * 4.0);
            case "MONEY_MARKET" -> 1.0 + (random.randomDouble() * 4.5);
            case "CD" -> 2.0 + (random.randomDouble() * 5.0);
            case "IRA" -> null;  // Varies by investment
            case "BROKERAGE" -> null;
            case "CREDIT_CARD" -> 15.0 + (random.randomDouble() * 15.0);
            case "LOAN" -> 5.0 + (random.randomDouble() * 15.0);
            case "MORTGAGE" -> 3.0 + (random.randomDouble() * 5.0);
            case "LINE_OF_CREDIT" -> 8.0 + (random.randomDouble() * 12.0);
            default -> null;
        };
    }
}
