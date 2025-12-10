package com.wf.benchmark.generator;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityGeneratorTest {

    private IdentityGenerator generator;
    private RandomDataProvider randomProvider;

    @BeforeEach
    void setUp() {
        randomProvider = new RandomDataProvider();
        generator = new IdentityGenerator(randomProvider, 0.7, "");
    }

    @Test
    void shouldGenerateDocumentWithCorrectStructure() {
        Document doc = generator.generate(0);

        assertThat(doc).isNotNull();
        assertThat(doc.get("_id")).isNotNull();
        assertThat(doc.get("common")).isNotNull();
        assertThat(doc.get("metaData")).isNotNull();
    }

    @Test
    void shouldGenerateCorrectCustomerNumber() {
        Document doc = generator.generate(0);
        Document id = doc.get("_id", Document.class);

        assertThat(id.getLong("customerNumber")).isEqualTo(1_000_000_001L);
    }

    @Test
    void shouldGenerateSequentialCustomerNumbers() {
        Document doc1 = generator.generate(0);
        Document doc2 = generator.generate(1);
        Document doc3 = generator.generate(100);

        Document id1 = doc1.get("_id", Document.class);
        Document id2 = doc2.get("_id", Document.class);
        Document id3 = doc3.get("_id", Document.class);

        assertThat(id1.getLong("customerNumber")).isEqualTo(1_000_000_001L);
        assertThat(id2.getLong("customerNumber")).isEqualTo(1_000_000_002L);
        assertThat(id3.getLong("customerNumber")).isEqualTo(1_000_000_101L);
    }

    @Test
    void shouldGenerateCommonSection() {
        Document doc = generator.generate(0);
        Document common = doc.get("common", Document.class);

        assertThat(common.getString("fullName")).isNotBlank();
        assertThat(common.getString("entityTypeIndicator"))
            .isIn("INDIVIDUAL", "NON_INDIVIDUAL");
        assertThat(common.getString("taxIdentificationTypeCode"))
            .isIn("SSN", "EIN", "ITIN");
        assertThat(common.getString("taxIdentificationNumber")).isNotBlank();
    }

    @Test
    void shouldGenerateIndividualSectionForIndividuals() {
        // Generate many documents to find an individual
        for (int i = 0; i < 100; i++) {
            Document doc = generator.generate(i);
            Document common = doc.get("common", Document.class);

            if ("INDIVIDUAL".equals(common.getString("entityTypeIndicator"))) {
                assertThat(doc.get("individual")).isNotNull();
                Document individual = doc.get("individual", Document.class);

                assertThat(individual.getString("firstName")).isNotBlank();
                assertThat(individual.getString("lastName")).isNotBlank();
                assertThat(individual.getString("birthDate")).isNotBlank();
                return;
            }
        }
    }

    @Test
    void shouldGenerateNonIndividualSectionForBusinesses() {
        // Generate many documents to find a business
        for (int i = 0; i < 100; i++) {
            Document doc = generator.generate(i);
            Document common = doc.get("common", Document.class);

            if ("NON_INDIVIDUAL".equals(common.getString("entityTypeIndicator"))) {
                assertThat(doc.get("nonIndividual")).isNotNull();
                Document nonIndividual = doc.get("nonIndividual", Document.class);

                assertThat(nonIndividual.getString("businessEntityTypeCode")).isNotBlank();
                assertThat(nonIndividual.getString("corporationTypeCode")).isNotBlank();
                return;
            }
        }
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
    void shouldReturnCorrectCollectionName() {
        assertThat(generator.getCollectionName()).isEqualTo("identity");
    }

    @Test
    void shouldApplyCollectionPrefix() {
        IdentityGenerator prefixedGenerator = new IdentityGenerator(randomProvider, 0.7, "test_");
        assertThat(prefixedGenerator.getCollectionName()).isEqualTo("test_identity");
    }
}
