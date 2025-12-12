package com.wf.benchmark.query;

import com.wf.benchmark.config.QueryConfig;
import com.wf.benchmark.config.QueryConfig.LookupDefinition;
import com.wf.benchmark.config.QueryConfig.QueryDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for MongoDB $lookup aggregation support.
 *
 * The MongoDB API for Oracle Database supports $lookup aggregation with key attributes.
 * These tests verify the configuration, parsing, and pipeline generation for $lookup queries.
 *
 * UC-1: Phone + SSN Last 4 (phone $lookup identity)
 * UC-2: Phone + SSN Last 4 + Account Last 4 (phone $lookup identity $lookup account)
 * UC-4: Account + SSN Last 4 (account $lookup identity)
 * UC-6: Email + Account Last 4 (identity $lookup account)
 */
class LookupAggregationTest {

    @Nested
    class LookupDefinitionTests {

        @Test
        void shouldCreateSimpleLookupDefinition() {
            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setLocalField("phoneKey.customerNumber");
            lookup.setForeignField("_id.customerNumber");
            lookup.setAs("identityDocs");

            assertThat(lookup.getFrom()).isEqualTo("identity");
            assertThat(lookup.getLocalField()).isEqualTo("phoneKey.customerNumber");
            assertThat(lookup.getForeignField()).isEqualTo("_id.customerNumber");
            assertThat(lookup.getAs()).isEqualTo("identityDocs");
        }

        @Test
        void shouldGenerateLookupStageDocument() {
            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setLocalField("phoneKey.customerNumber");
            lookup.setForeignField("_id.customerNumber");
            lookup.setAs("identityDocs");

            Document lookupStage = lookup.toLookupStage();

            assertThat(lookupStage.containsKey("$lookup")).isTrue();
            Document lookupDoc = lookupStage.get("$lookup", Document.class);
            assertThat(lookupDoc.getString("from")).isEqualTo("identity");
            assertThat(lookupDoc.getString("localField")).isEqualTo("phoneKey.customerNumber");
            assertThat(lookupDoc.getString("foreignField")).isEqualTo("_id.customerNumber");
            assertThat(lookupDoc.getString("as")).isEqualTo("identityDocs");
        }

        @Test
        void shouldGenerateUnwindStageDocument() {
            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setAs("identityDocs");

            Document unwindStage = lookup.toUnwindStage();

            assertThat(unwindStage.containsKey("$unwind")).isTrue();
            Document unwindDoc = unwindStage.get("$unwind", Document.class);
            assertThat(unwindDoc.getString("path")).isEqualTo("$identityDocs");
            assertThat(unwindDoc.getBoolean("preserveNullAndEmptyArrays")).isFalse();
        }

        @Test
        void shouldSupportOptionalMatchAfterLookup() {
            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setLocalField("phoneKey.customerNumber");
            lookup.setForeignField("_id.customerNumber");
            lookup.setAs("identityDocs");
            lookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            assertThat(lookup.hasMatchFilter()).isTrue();
            assertThat(lookup.getMatchFilter()).isNotNull();
        }

        @Test
        void shouldGenerateMatchStageDocument() {
            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setAs("identityDocs");
            lookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "1234"));

            Document matchStage = lookup.toMatchStage();

            assertThat(matchStage.containsKey("$match")).isTrue();
            Document matchDoc = matchStage.get("$match", Document.class);
            assertThat(matchDoc.getString("identityDocs.common.taxIdentificationNumberLast4")).isEqualTo("1234");
        }

        @Test
        void shouldSupportChainedLookups() {
            LookupDefinition identityLookup = new LookupDefinition();
            identityLookup.setFrom("identity");
            identityLookup.setLocalField("phoneKey.customerNumber");
            identityLookup.setForeignField("_id.customerNumber");
            identityLookup.setAs("identityDocs");
            identityLookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            LookupDefinition accountLookup = new LookupDefinition();
            accountLookup.setFrom("account");
            accountLookup.setLocalField("identityDocs._id.customerNumber");
            accountLookup.setForeignField("accountHolders.customerNumber");
            accountLookup.setAs("accountDocs");
            accountLookup.setMatchFilter(new Document("accountDocs.accountKey.accountNumberLast4", "${param:accountLast4}"));

            identityLookup.setNextLookup(accountLookup);

            assertThat(identityLookup.getNextLookup()).isNotNull();
            assertThat(identityLookup.getNextLookup().getFrom()).isEqualTo("account");
        }
    }

    @Nested
    class QueryDefinitionWithLookupTests {

        @Test
        void shouldParseQueryDefinitionWithLookup() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc1_phone_ssn_last4_lookup");
            queryDef.setCollection("phone");
            queryDef.setType("aggregate");
            queryDef.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setLocalField("phoneKey.customerNumber");
            lookup.setForeignField("_id.customerNumber");
            lookup.setAs("identityDocs");
            lookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            queryDef.setLookup(lookup);

            assertThat(queryDef.getLookup()).isNotNull();
            assertThat(queryDef.hasLookup()).isTrue();
            assertThat(queryDef.getLookup().getFrom()).isEqualTo("identity");
        }

        @Test
        void shouldReturnFalseForQueryWithoutLookup() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("simple_query");
            queryDef.setCollection("identity");
            queryDef.setType("find");

            assertThat(queryDef.hasLookup()).isFalse();
        }
    }

    @Nested
    class PipelineGenerationTests {

        @Test
        void shouldGenerateSimpleLookupPipeline() {
            // UC-1: Phone + SSN Last 4 using $lookup
            QueryDefinition queryDef = createUC1LookupQueryDefinition();

            List<Document> pipeline = queryDef.generateLookupPipeline();

            assertThat(pipeline).isNotNull();
            assertThat(pipeline).hasSize(4); // $match, $lookup, $unwind, $match (for SSN filter)

            // First stage: $match on phone number
            Document matchStage = pipeline.get(0);
            assertThat(matchStage.containsKey("$match")).isTrue();

            // Second stage: $lookup
            Document lookupStage = pipeline.get(1);
            assertThat(lookupStage.containsKey("$lookup")).isTrue();

            // Third stage: $unwind
            Document unwindStage = pipeline.get(2);
            assertThat(unwindStage.containsKey("$unwind")).isTrue();

            // Fourth stage: $match for SSN filter
            Document ssnMatchStage = pipeline.get(3);
            assertThat(ssnMatchStage.containsKey("$match")).isTrue();
        }

        @Test
        void shouldGenerateChainedLookupPipeline() {
            // UC-2: Phone + SSN + Account using chained $lookup
            QueryDefinition queryDef = createUC2LookupQueryDefinition();

            List<Document> pipeline = queryDef.generateLookupPipeline();

            assertThat(pipeline).isNotNull();
            // $match, $lookup(identity), $unwind, $match(ssn), $lookup(account), $unwind, $match(account)
            assertThat(pipeline).hasSize(7);

            // Verify first lookup is to identity
            Document firstLookup = pipeline.get(1);
            assertThat(firstLookup.get("$lookup", Document.class).getString("from")).isEqualTo("identity");

            // Verify second lookup is to account
            Document secondLookup = pipeline.get(4);
            assertThat(secondLookup.get("$lookup", Document.class).getString("from")).isEqualTo("account");
        }

        @Test
        void shouldGenerateUC4LookupPipeline() {
            // UC-4: Account + SSN
            QueryDefinition queryDef = createUC4LookupQueryDefinition();

            List<Document> pipeline = queryDef.generateLookupPipeline();

            assertThat(pipeline).hasSize(4);

            // First stage: $match on account number
            Document matchStage = pipeline.get(0);
            assertThat(matchStage.get("$match", Document.class).containsKey("accountKey.accountNumber")).isTrue();

            // Second stage: $lookup to identity
            Document lookupStage = pipeline.get(1);
            assertThat(lookupStage.get("$lookup", Document.class).getString("from")).isEqualTo("identity");
        }

        @Test
        void shouldGenerateUC6LookupPipeline() {
            // UC-6: Email + Account Last 4
            QueryDefinition queryDef = createUC6LookupQueryDefinition();

            List<Document> pipeline = queryDef.generateLookupPipeline();

            assertThat(pipeline).hasSize(4);

            // First stage: $match on email
            Document matchStage = pipeline.get(0);
            assertThat(matchStage.get("$match", Document.class).containsKey("emails.emailAddress")).isTrue();

            // Second stage: $lookup to account
            Document lookupStage = pipeline.get(1);
            assertThat(lookupStage.get("$lookup", Document.class).getString("from")).isEqualTo("account");
        }

        private QueryDefinition createUC1LookupQueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc1_phone_ssn_last4_lookup");
            queryDef.setDescription("UC-1: Phone + SSN Last 4 via $lookup aggregation");
            queryDef.setCollection("phone");
            queryDef.setType("aggregate");
            queryDef.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setLocalField("phoneKey.customerNumber");
            lookup.setForeignField("_id.customerNumber");
            lookup.setAs("identityDocs");
            lookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            queryDef.setLookup(lookup);
            return queryDef;
        }

        private QueryDefinition createUC2LookupQueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc2_phone_ssn_account_lookup");
            queryDef.setDescription("UC-2: Phone + SSN + Account via chained $lookup");
            queryDef.setCollection("phone");
            queryDef.setType("aggregate");
            queryDef.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            // First lookup: phone -> identity
            LookupDefinition identityLookup = new LookupDefinition();
            identityLookup.setFrom("identity");
            identityLookup.setLocalField("phoneKey.customerNumber");
            identityLookup.setForeignField("_id.customerNumber");
            identityLookup.setAs("identityDocs");
            identityLookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            // Second lookup: identity -> account
            LookupDefinition accountLookup = new LookupDefinition();
            accountLookup.setFrom("account");
            accountLookup.setLocalField("identityDocs._id.customerNumber");
            accountLookup.setForeignField("accountHolders.customerNumber");
            accountLookup.setAs("accountDocs");
            accountLookup.setMatchFilter(new Document("accountDocs.accountKey.accountNumberLast4", "${param:accountLast4}"));

            identityLookup.setNextLookup(accountLookup);
            queryDef.setLookup(identityLookup);

            return queryDef;
        }

        private QueryDefinition createUC4LookupQueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc4_account_ssn_lookup");
            queryDef.setDescription("UC-4: Account + SSN via $lookup");
            queryDef.setCollection("account");
            queryDef.setType("aggregate");
            queryDef.setFilter(new Document("accountKey.accountNumber", "${param:accountNumber}"));

            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setLocalField("accountHolders.customerNumber");
            lookup.setForeignField("_id.customerNumber");
            lookup.setAs("identityDocs");
            lookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            queryDef.setLookup(lookup);
            return queryDef;
        }

        private QueryDefinition createUC6LookupQueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc6_email_account_lookup");
            queryDef.setDescription("UC-6: Email + Account Last 4 via $lookup");
            queryDef.setCollection("identity");
            queryDef.setType("aggregate");
            queryDef.setFilter(new Document("emails.emailAddress", "${param:email}"));

            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("account");
            lookup.setLocalField("_id.customerNumber");
            lookup.setForeignField("accountHolders.customerNumber");
            lookup.setAs("accountDocs");
            lookup.setMatchFilter(new Document("accountDocs.accountKey.accountNumberLast4", "${param:accountLast4}"));

            queryDef.setLookup(lookup);
            return queryDef;
        }
    }

    @Nested
    class YamlParsingTests {

        @Test
        void shouldParseLookupFromYamlMap() {
            Map<String, Object> lookupMap = Map.of(
                "from", "identity",
                "localField", "phoneKey.customerNumber",
                "foreignField", "_id.customerNumber",
                "as", "identityDocs",
                "matchFilter", Map.of("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}")
            );

            LookupDefinition lookup = LookupDefinition.fromMap(lookupMap);

            assertThat(lookup.getFrom()).isEqualTo("identity");
            assertThat(lookup.getLocalField()).isEqualTo("phoneKey.customerNumber");
            assertThat(lookup.getForeignField()).isEqualTo("_id.customerNumber");
            assertThat(lookup.getAs()).isEqualTo("identityDocs");
            assertThat(lookup.hasMatchFilter()).isTrue();
        }

        @Test
        void shouldParseChainedLookupsFromYamlMap() {
            Map<String, Object> accountLookupMap = Map.of(
                "from", "account",
                "localField", "identityDocs._id.customerNumber",
                "foreignField", "accountHolders.customerNumber",
                "as", "accountDocs",
                "matchFilter", Map.of("accountDocs.accountKey.accountNumberLast4", "${param:accountLast4}")
            );

            Map<String, Object> identityLookupMap = Map.of(
                "from", "identity",
                "localField", "phoneKey.customerNumber",
                "foreignField", "_id.customerNumber",
                "as", "identityDocs",
                "matchFilter", Map.of("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"),
                "lookup", accountLookupMap
            );

            LookupDefinition lookup = LookupDefinition.fromMap(identityLookupMap);

            assertThat(lookup.getFrom()).isEqualTo("identity");
            assertThat(lookup.getNextLookup()).isNotNull();
            assertThat(lookup.getNextLookup().getFrom()).isEqualTo("account");
        }
    }

    @Nested
    class ParameterSubstitutionTests {

        @Test
        void shouldSubstituteParametersInLookupMatchFilter() {
            LookupDefinition lookup = new LookupDefinition();
            lookup.setFrom("identity");
            lookup.setLocalField("phoneKey.customerNumber");
            lookup.setForeignField("_id.customerNumber");
            lookup.setAs("identityDocs");
            lookup.setMatchFilter(new Document("identityDocs.common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            // Parameter substitution should be handled during pipeline generation
            assertThat(lookup.getMatchFilter().getString("identityDocs.common.taxIdentificationNumberLast4"))
                .isEqualTo("${param:ssnLast4}");
        }
    }
}
