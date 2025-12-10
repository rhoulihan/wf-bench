package com.wf.benchmark.generator;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AddressGeneratorTest {

    private AddressGenerator generator;
    private RandomDataProvider randomProvider;

    @BeforeEach
    void setUp() {
        randomProvider = new RandomDataProvider();
        generator = new AddressGenerator(randomProvider, 1, 4, "");
    }

    @Test
    void shouldGenerateDocumentWithCorrectStructure() {
        Document doc = generator.generate(0);

        assertThat(doc).isNotNull();
        assertThat(doc.get("_id")).isNotNull();
        assertThat(doc.get("addresses")).isNotNull();
    }

    @Test
    void shouldGenerateCorrectCustomerNumber() {
        Document doc = generator.generate(0);
        Document id = doc.get("_id", Document.class);

        assertThat(id.getLong("customerNumber")).isEqualTo(1_000_000_001L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldGenerateAddressesArray() {
        Document doc = generator.generate(0);
        List<Document> addresses = (List<Document>) doc.get("addresses");

        assertThat(addresses).isNotEmpty();
        assertThat(addresses.size()).isBetween(1, 4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldGenerateAddressWithRequiredFields() {
        Document doc = generator.generate(0);
        List<Document> addresses = (List<Document>) doc.get("addresses");
        Document address = addresses.get(0);

        assertThat(address.getString("addressUseCode")).isNotBlank();
        assertThat(address.get("addressLines")).isNotNull();
        assertThat(address.getString("cityName")).isNotBlank();
        assertThat(address.getString("stateCode")).isNotBlank();
        assertThat(address.getString("postalCode")).isNotBlank();
        assertThat(address.getString("countryCode")).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldGenerateFirstAddressAsCustomerResidence() {
        Document doc = generator.generate(0);
        List<Document> addresses = (List<Document>) doc.get("addresses");
        Document firstAddress = addresses.get(0);

        assertThat(firstAddress.getString("addressUseCode")).isEqualTo("CUSTOMER_RESIDENCE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldGenerateAddressMetaData() {
        Document doc = generator.generate(0);
        List<Document> addresses = (List<Document>) doc.get("addresses");
        Document address = addresses.get(0);
        Document metaData = address.get("metaData", Document.class);

        assertThat(metaData).isNotNull();
        assertThat(metaData.getString("createdByProcessName")).isNotBlank();
        assertThat(metaData.get("createdTimestamp")).isNotNull();
        assertThat(metaData.getString("cdcDeletedFlag")).isEqualTo("N");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldGenerateSystemOfRecord() {
        Document doc = generator.generate(0);
        List<Document> addresses = (List<Document>) doc.get("addresses");
        Document address = addresses.get(0);
        Document systemOfRecord = address.get("systemOfRecord", Document.class);

        assertThat(systemOfRecord).isNotNull();
        assertThat(systemOfRecord.getString("addressKey")).isNotBlank();
        assertThat(systemOfRecord.getInteger("addressUseOccurrenceNumber")).isPositive();
    }

    @Test
    void shouldReturnCorrectCollectionName() {
        assertThat(generator.getCollectionName()).isEqualTo("address");
    }

    @Test
    void shouldApplyCollectionPrefix() {
        AddressGenerator prefixedGenerator = new AddressGenerator(randomProvider, 1, 4, "test_");
        assertThat(prefixedGenerator.getCollectionName()).isEqualTo("test_address");
    }
}
