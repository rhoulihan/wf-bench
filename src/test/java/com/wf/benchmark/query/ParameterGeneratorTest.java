package com.wf.benchmark.query;

import com.wf.benchmark.config.QueryConfig.ParameterDefinition;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterGeneratorTest {

    private Map<String, ParameterDefinition> parameterDefs;

    @BeforeEach
    void setUp() {
        parameterDefs = new HashMap<>();
    }

    @Nested
    class RandomPatternTests {

        @Test
        void shouldGenerateFourDigitPattern() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_pattern");
            paramDef.setPattern("\\d{4}");
            parameterDefs.put("ssnLast4", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("field", "${param:ssnLast4}");

            Document result = generator.substituteParameters(filter);

            String value = (String) result.get("field");
            assertThat(value).matches("\\d{4}");
            assertThat(value).hasSize(4);
        }

        @Test
        void shouldGenerateFiveDigitZipPattern() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_pattern");
            paramDef.setPattern("\\d{5}");
            parameterDefs.put("zip", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("postalCode", "${param:zip}");

            Document result = generator.substituteParameters(filter);

            String value = (String) result.get("postalCode");
            assertThat(value).matches("\\d{5}");
            assertThat(value).hasSize(5);
        }

        @Test
        void shouldGeneratePhoneNumberPattern() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_pattern");
            paramDef.setPattern("\\d{3}-\\d{3}-\\d{4}");
            parameterDefs.put("phone", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("phoneNumber", "${param:phone}");

            Document result = generator.substituteParameters(filter);

            String value = (String) result.get("phoneNumber");
            assertThat(value).matches("\\d{3}-\\d{3}-\\d{4}");
        }

        @Test
        void shouldGenerateAccountNumberPattern() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_pattern");
            paramDef.setPattern("\\d{12}");
            parameterDefs.put("accountNumber", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("accountNumber", "${param:accountNumber}");

            Document result = generator.substituteParameters(filter);

            String value = (String) result.get("accountNumber");
            assertThat(value).matches("\\d{12}");
            assertThat(value).hasSize(12);
        }

        @Test
        void shouldGenerateAlphanumericPattern() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_pattern");
            paramDef.setPattern("[A-Z]{2}\\d{6}");
            parameterDefs.put("code", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("code", "${param:code}");

            Document result = generator.substituteParameters(filter);

            String value = (String) result.get("code");
            assertThat(value).matches("[A-Z]{2}\\d{6}");
            assertThat(value).hasSize(8);
        }

        @Test
        void shouldGenerateSSNPattern() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_pattern");
            paramDef.setPattern("\\d{3}-\\d{2}-\\d{4}");
            parameterDefs.put("ssn", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("ssn", "${param:ssn}");

            Document result = generator.substituteParameters(filter);

            String value = (String) result.get("ssn");
            assertThat(value).matches("\\d{3}-\\d{2}-\\d{4}");
        }

        @Test
        void shouldGenerateDifferentValuesOnMultipleCalls() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_pattern");
            paramDef.setPattern("\\d{4}");
            parameterDefs.put("ssnLast4", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("field", "${param:ssnLast4}");

            // Generate multiple values and check they're not all the same
            java.util.Set<String> values = new java.util.HashSet<>();
            for (int i = 0; i < 100; i++) {
                Document result = generator.substituteParameters(filter);
                values.add((String) result.get("field"));
            }

            // Should have variety (statistically unlikely to get only one value in 100 tries)
            assertThat(values.size()).isGreaterThan(1);
        }
    }

    @Nested
    class ExistingParameterTypeTests {

        @Test
        void shouldGenerateRandomRange() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_range");
            paramDef.setMin(100L);
            paramDef.setMax(200L);
            parameterDefs.put("num", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("value", "${param:num}");

            Document result = generator.substituteParameters(filter);

            long value = (Long) result.get("value");
            assertThat(value).isBetween(100L, 200L);
        }

        @Test
        void shouldGenerateRandomChoice() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_choice");
            paramDef.setValues(List.of("A", "B", "C"));
            parameterDefs.put("choice", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("value", "${param:choice}");

            Document result = generator.substituteParameters(filter);

            String value = (String) result.get("value");
            assertThat(value).isIn("A", "B", "C");
        }

        @Test
        void shouldGenerateSequential() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("sequential");
            paramDef.setMin(1L);
            paramDef.setMax(1000L);
            parameterDefs.put("seq", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("value", "${param:seq}");

            Document result1 = generator.substituteParameters(filter);
            Document result2 = generator.substituteParameters(filter);
            Document result3 = generator.substituteParameters(filter);

            assertThat(result1.get("value")).isEqualTo(1L);
            assertThat(result2.get("value")).isEqualTo(2L);
            assertThat(result3.get("value")).isEqualTo(3L);
        }

        @Test
        void shouldGenerateFixed() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("fixed");
            paramDef.setFixedValue("FIXED_VALUE");
            parameterDefs.put("fixed", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("value", "${param:fixed}");

            Document result = generator.substituteParameters(filter);

            assertThat(result.get("value")).isEqualTo("FIXED_VALUE");
        }

        @Test
        void shouldThrowForUnknownParameter() {
            // Add a known parameter so parameterDefs is not empty
            ParameterDefinition knownParam = new ParameterDefinition();
            knownParam.setType("fixed");
            knownParam.setFixedValue("value");
            parameterDefs.put("known", knownParam);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("value", "${param:unknown}");

            assertThatThrownBy(() -> generator.substituteParameters(filter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown parameter: unknown");
        }
    }

    @Nested
    class NestedDocumentTests {

        @Test
        void shouldSubstituteInNestedDocument() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("fixed");
            paramDef.setFixedValue("test");
            parameterDefs.put("value", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("outer", new Document("inner", "${param:value}"));

            Document result = generator.substituteParameters(filter);

            Document outer = result.get("outer", Document.class);
            assertThat(outer.get("inner")).isEqualTo("test");
        }

        @Test
        void shouldSubstituteInList() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("fixed");
            paramDef.setFixedValue("test");
            parameterDefs.put("value", paramDef);

            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("values", List.of("${param:value}", "static"));

            Document result = generator.substituteParameters(filter);

            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) result.get("values");
            assertThat(values).containsExactly("test", "static");
        }
    }

    @Nested
    class RandomFromLoadedTests {

        @BeforeEach
        void setUp() {
            parameterDefs = new HashMap<>();
        }

        @Test
        void shouldThrowWhenDatabaseNotConfigured() {
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_from_loaded");
            paramDef.setCollection("phone");
            paramDef.setField("phoneKey.phoneNumber");
            parameterDefs.put("phoneNumber", paramDef);

            // Create generator without database
            ParameterGenerator generator = new ParameterGenerator(parameterDefs);
            Document filter = new Document("phone", "${param:phoneNumber}");

            assertThatThrownBy(() -> generator.substituteParameters(filter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database not configured");
        }

        @Test
        void shouldAcceptDatabaseInConstructor() {
            // Just verify we can create a generator with database parameter
            // Actual database testing would be integration tests
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("fixed");
            paramDef.setFixedValue("test");
            parameterDefs.put("value", paramDef);

            // Should not throw with null database for non-random_from_loaded types
            ParameterGenerator generator = new ParameterGenerator(parameterDefs, null);
            Document filter = new Document("field", "${param:value}");

            Document result = generator.substituteParameters(filter);
            assertThat(result.get("field")).isEqualTo("test");
        }

        @Test
        void shouldHaveCorrectParameterDefinitionFields() {
            // Test that ParameterDefinition has the required fields for random_from_loaded
            ParameterDefinition paramDef = new ParameterDefinition();
            paramDef.setType("random_from_loaded");
            paramDef.setCollection("identity");
            paramDef.setField("common.taxIdentificationNumber");

            assertThat(paramDef.getType()).isEqualTo("random_from_loaded");
            assertThat(paramDef.getCollection()).isEqualTo("identity");
            assertThat(paramDef.getField()).isEqualTo("common.taxIdentificationNumber");
        }
    }
}
