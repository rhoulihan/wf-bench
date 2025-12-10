package com.wf.benchmark.generator;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneGeneratorTest {

    private PhoneGenerator generator;
    private RandomDataProvider randomProvider;

    @BeforeEach
    void setUp() {
        randomProvider = new RandomDataProvider();
        generator = new PhoneGenerator(randomProvider, 2.5, "");
    }

    @Test
    void shouldGenerateDocumentWithCorrectStructure() {
        Document doc = generator.generate(0);

        assertThat(doc).isNotNull();
        assertThat(doc.get("_id")).isInstanceOf(ObjectId.class);
        assertThat(doc.get("phoneKey")).isNotNull();
        assertThat(doc.get("metaData")).isNotNull();
    }

    @Test
    void shouldGeneratePhoneKeyWithCustomerReference() {
        Document doc = generator.generate(0);
        Document phoneKey = doc.get("phoneKey", Document.class);

        assertThat(phoneKey.getLong("customerNumber")).isEqualTo(1_000_000_001L);
        assertThat(phoneKey.getInteger("customerCompanyNumber")).isBetween(1, 3);
        assertThat(phoneKey.getString("phoneNumber")).matches("\\d{10}");
        assertThat(phoneKey.getString("phoneNumberTypeCode"))
            .isIn("MOBILE", "HOME", "BUSINESS", "FAX");
    }

    @Test
    void shouldGenerateFirstPhoneAsMobile() {
        Document doc = generator.generate(0);
        Document phoneKey = doc.get("phoneKey", Document.class);

        assertThat(phoneKey.getString("phoneNumberTypeCode")).isEqualTo("MOBILE");
    }

    @Test
    void shouldGenerateLineTypeCode() {
        Document doc = generator.generate(0);

        assertThat(doc.getString("lineTypeCode"))
            .isIn("WIRELESS", "LANDLINE");
    }

    @Test
    void shouldGenerateProviderName() {
        Document doc = generator.generate(0);

        assertThat(doc.getString("providerName")).isNotBlank();
    }

    @Test
    void shouldGenerateCountryCode() {
        Document doc = generator.generate(0);

        assertThat(doc.getString("countryCode")).isIn("US", "MX");
    }

    @Test
    void shouldGenerateMetaData() {
        Document doc = generator.generate(0);
        Document metaData = doc.get("metaData", Document.class);

        assertThat(metaData.getString("createdByProcessName")).isNotBlank();
        assertThat(metaData.getString("createdByIdentifier")).isNotBlank();
        assertThat(metaData.get("createdTimestamp")).isNotNull();
        assertThat(metaData.getString("cdcDeletedFlag")).isEqualTo("N");
    }

    @Test
    void shouldGenerateSystemOfRecord() {
        Document doc = generator.generate(0);
        Document systemOfRecord = doc.get("systemOfRecord", Document.class);

        assertThat(systemOfRecord).isNotNull();
        assertThat(systemOfRecord.getString("M_XCM_DEVICE_TYPE")).isIn("MOBIL", "LAND");
    }

    @Test
    void shouldMapMultiplePhonesPerCustomer() {
        // With ratio 2.5, first 2-3 phones should belong to customer 1
        Document doc0 = generator.generate(0);
        Document doc1 = generator.generate(1);
        Document doc2 = generator.generate(2);

        Document key0 = doc0.get("phoneKey", Document.class);
        Document key1 = doc1.get("phoneKey", Document.class);
        Document key2 = doc2.get("phoneKey", Document.class);

        // Sequence 0, 1 map to customer 0 (1000000001)
        assertThat(key0.getLong("customerNumber")).isEqualTo(1_000_000_001L);
        assertThat(key1.getLong("customerNumber")).isEqualTo(1_000_000_001L);
        // Sequence 2 maps to customer 0 or 1 depending on ratio math
    }

    @Test
    void shouldReturnCorrectCollectionName() {
        assertThat(generator.getCollectionName()).isEqualTo("phone");
    }

    @Test
    void shouldApplyCollectionPrefix() {
        PhoneGenerator prefixedGenerator = new PhoneGenerator(randomProvider, 2.5, "test_");
        assertThat(prefixedGenerator.getCollectionName()).isEqualTo("test_phone");
    }

    @Test
    void shouldSetIsPreferredForFirstPhone() {
        Document doc = generator.generate(0);
        assertThat(doc.getBoolean("isPreferred")).isTrue();
    }
}
