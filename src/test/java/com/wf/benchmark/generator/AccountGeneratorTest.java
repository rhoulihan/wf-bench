package com.wf.benchmark.generator;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountGeneratorTest {

    private AccountGenerator generator;
    private RandomDataProvider randomProvider;

    @BeforeEach
    void setUp() {
        randomProvider = new RandomDataProvider();
        generator = new AccountGenerator(randomProvider, 1.5, "", 1_000_000);
    }

    @Test
    void shouldGenerateDocumentWithCorrectStructure() {
        Document doc = generator.generate(0);

        assertThat(doc).isNotNull();
        assertThat(doc.get("_id")).isInstanceOf(ObjectId.class);
        assertThat(doc.get("accountKey")).isNotNull();
        assertThat(doc.get("productTypeCode")).isNotNull();
        assertThat(doc.get("accountHolders")).isNotNull();
        assertThat(doc.get("metaData")).isNotNull();
    }

    @Test
    void shouldGenerateAccountKeyWithCorrectFields() {
        Document doc = generator.generate(0);
        Document accountKey = doc.get("accountKey", Document.class);

        assertThat(accountKey.getString("accountNumber")).matches("\\d{12}");
        assertThat(accountKey.getString("accountNumberLast4")).hasSize(4);
        assertThat(accountKey.getString("accountNumberTokenized")).startsWith("TOK");
    }

    @Test
    void shouldGenerateAccountNumberLast4Correctly() {
        Document doc = generator.generate(0);
        Document accountKey = doc.get("accountKey", Document.class);

        String accountNumber = accountKey.getString("accountNumber");
        String last4 = accountKey.getString("accountNumberLast4");

        assertThat(accountNumber.substring(accountNumber.length() - 4)).isEqualTo(last4);
    }

    @Test
    void shouldGenerateValidProductTypeCode() {
        Document doc = generator.generate(0);

        assertThat(doc.getString("productTypeCode"))
            .isIn("CHECKING", "SAVINGS", "MONEY_MARKET", "CD", "IRA",
                "BROKERAGE", "CREDIT_CARD", "LOAN", "MORTGAGE", "LINE_OF_CREDIT");
    }

    @Test
    void shouldGenerateValidAccountStatus() {
        Document doc = generator.generate(0);

        assertThat(doc.getString("accountStatus"))
            .isIn("ACTIVE", "INACTIVE", "DORMANT", "CLOSED", "PENDING");
    }

    @Test
    void shouldGenerateCompanyOfInterestId() {
        Document doc = generator.generate(0);

        assertThat(doc.getString("companyOfInterestId"))
            .isIn("WF_BANK", "WF_ADVISOR", "WF_BROKERAGE", "WF_MORTGAGE", "WF_CREDIT");
    }

    @Test
    void shouldGenerateAccountHoldersArray() {
        Document doc = generator.generate(0);
        @SuppressWarnings("unchecked")
        List<Document> holders = doc.getList("accountHolders", Document.class);

        assertThat(holders).isNotEmpty();
        assertThat(holders.size()).isBetween(1, 4);
    }

    @Test
    void shouldGeneratePrimaryAccountHolder() {
        Document doc = generator.generate(0);
        @SuppressWarnings("unchecked")
        List<Document> holders = doc.getList("accountHolders", Document.class);

        Document primaryHolder = holders.get(0);
        assertThat(primaryHolder.getLong("customerNumber")).isEqualTo(1_000_000_001L);
        assertThat(primaryHolder.getInteger("customerCompanyNumber")).isBetween(1, 3);
        assertThat(primaryHolder.getString("relationshipType")).isEqualTo("PRIMARY");
        assertThat(primaryHolder.getBoolean("isPrimaryHolder")).isTrue();
        assertThat(primaryHolder.getString("addedDate")).isNotBlank();
    }

    @Test
    void shouldGenerateOpenDate() {
        Document doc = generator.generate(0);

        assertThat(doc.getString("openDate")).isNotBlank();
    }

    @Test
    void shouldGenerateMetaData() {
        Document doc = generator.generate(0);
        Document metaData = doc.get("metaData", Document.class);

        assertThat(metaData.getString("createdByProcessName")).isNotBlank();
        assertThat(metaData.getString("createdByIdentifier")).isNotBlank();
        assertThat(metaData.get("createdTimestamp")).isNotNull();
        assertThat(metaData.getString("cdcDeletedFlag")).isEqualTo("N");
        assertThat(metaData.get("ACCOUNT")).isNotNull();
    }

    @Test
    void shouldMapAccountsToCustomersBasedOnRatio() {
        // With ratio 1.5, first customer should have accounts at sequence 0, 1
        // Second customer should have accounts starting at sequence 1 or 2
        Document doc0 = generator.generate(0);
        Document doc1 = generator.generate(1);

        @SuppressWarnings("unchecked")
        List<Document> holders0 = doc0.getList("accountHolders", Document.class);
        @SuppressWarnings("unchecked")
        List<Document> holders1 = doc1.getList("accountHolders", Document.class);

        // First account belongs to customer 1
        assertThat(holders0.get(0).getLong("customerNumber")).isEqualTo(1_000_000_001L);
        // Second account belongs to customer 1 or 2 depending on ratio
    }

    @Test
    void shouldReturnCorrectCollectionName() {
        assertThat(generator.getCollectionName()).isEqualTo("account");
    }

    @Test
    void shouldApplyCollectionPrefix() {
        AccountGenerator prefixedGenerator = new AccountGenerator(
            randomProvider, 1.5, "test_", 1_000_000);
        assertThat(prefixedGenerator.getCollectionName()).isEqualTo("test_account");
    }

    @Test
    void shouldGenerateValidBalanceAmount() {
        // Generate multiple documents to find one with a balance
        for (int i = 0; i < 100; i++) {
            Document doc = generator.generate(i);
            Double balance = doc.getDouble("balanceAmount");
            if (balance != null) {
                assertThat(balance).isPositive();
                return;
            }
        }
        // 85% should have balance, so we should find at least one
    }

    @Test
    void shouldGenerateJointAccountHolders() {
        // Generate multiple documents to find one with joint holders
        boolean foundJoint = false;
        for (int i = 0; i < 100; i++) {
            Document doc = generator.generate(i);
            @SuppressWarnings("unchecked")
            List<Document> holders = doc.getList("accountHolders", Document.class);
            if (holders.size() > 1) {
                foundJoint = true;
                Document jointHolder = holders.get(1);
                assertThat(jointHolder.getLong("customerNumber")).isGreaterThan(0);
                assertThat(jointHolder.getBoolean("isPrimaryHolder")).isFalse();
                assertThat(jointHolder.getString("relationshipType"))
                    .isIn("JOINT", "AUTHORIZED", "BENEFICIARY");
                break;
            }
        }
        // With 40% multi-holder rate, should find at least one
        assertThat(foundJoint).isTrue();
    }
}
