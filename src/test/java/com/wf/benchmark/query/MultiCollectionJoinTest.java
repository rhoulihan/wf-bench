package com.wf.benchmark.query;

import com.wf.benchmark.config.QueryConfig;
import com.wf.benchmark.config.QueryConfig.JoinDefinition;
import com.wf.benchmark.config.QueryConfig.QueryDefinition;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for multi-collection join functionality.
 *
 * These tests verify the join definitions and query execution for the
 * Wells Fargo use cases that require querying across multiple collections:
 *
 * UC-1: Phone + SSN Last 4 (phone -> identity)
 * UC-2: Phone + SSN Last 4 + Account Last 4 (phone -> identity -> account)
 * UC-3: Phone + Account Last 4 (phone -> identity -> account)
 * UC-4: Account + SSN Last 4 (account -> identity)
 * UC-5: Address + SSN Last 4 + Account Last 4 (address -> identity -> account)
 * UC-6: Email + Account Last 4 (identity -> account)
 * UC-7: Email + Phone + Account Last 4 (identity -> phone + account)
 */
class MultiCollectionJoinTest {

    @Nested
    class JoinDefinitionTests {

        @Test
        void shouldCreateSimpleJoinDefinition() {
            JoinDefinition join = new JoinDefinition();
            join.setCollection("identity");
            join.setLocalField("phoneKey.customerNumber");
            join.setForeignField("_id.customerNumber");
            join.setFilter(new Document("common.taxIdentificationNumberLast4", "1234"));

            assertThat(join.getCollection()).isEqualTo("identity");
            assertThat(join.getLocalField()).isEqualTo("phoneKey.customerNumber");
            assertThat(join.getForeignField()).isEqualTo("_id.customerNumber");
            assertThat(join.getFilter()).isNotNull();
            assertThat(join.getFilter().getString("common.taxIdentificationNumberLast4")).isEqualTo("1234");
        }

        @Test
        void shouldCreateChainedJoinDefinition() {
            // First join: phone -> identity
            JoinDefinition firstJoin = new JoinDefinition();
            firstJoin.setCollection("identity");
            firstJoin.setLocalField("phoneKey.customerNumber");
            firstJoin.setForeignField("_id.customerNumber");
            firstJoin.setFilter(new Document("common.taxIdentificationNumberLast4", "1234"));

            // Second join: identity -> account (chained)
            JoinDefinition secondJoin = new JoinDefinition();
            secondJoin.setCollection("account");
            secondJoin.setLocalField("_id.customerNumber");
            secondJoin.setForeignField("accountHolders.customerNumber");
            secondJoin.setFilter(new Document("accountKey.accountNumberLast4", "5678"));

            // Chain the joins
            firstJoin.setNextJoin(secondJoin);

            assertThat(firstJoin.getNextJoin()).isNotNull();
            assertThat(firstJoin.getNextJoin().getCollection()).isEqualTo("account");
        }

        @Test
        void shouldSupportMultipleJoinChain() {
            // UC-2: phone -> identity (SSN filter) -> account (account last 4 filter)
            JoinDefinition identityJoin = new JoinDefinition();
            identityJoin.setCollection("identity");
            identityJoin.setLocalField("phoneKey.customerNumber");
            identityJoin.setForeignField("_id.customerNumber");
            identityJoin.setFilter(new Document("common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            JoinDefinition accountJoin = new JoinDefinition();
            accountJoin.setCollection("account");
            accountJoin.setLocalField("_id.customerNumber");
            accountJoin.setForeignField("accountHolders.customerNumber");
            accountJoin.setFilter(new Document("accountKey.accountNumberLast4", "${param:accountLast4}"));

            identityJoin.setNextJoin(accountJoin);

            assertThat(identityJoin.getNextJoin()).isEqualTo(accountJoin);
            assertThat(accountJoin.getNextJoin()).isNull();
        }
    }

    @Nested
    class QueryDefinitionWithJoinTests {

        @Test
        void shouldParseQueryDefinitionWithJoin() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc1_phone_ssn_last4");
            queryDef.setCollection("phone");
            queryDef.setType("find");
            queryDef.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            JoinDefinition join = new JoinDefinition();
            join.setCollection("identity");
            join.setLocalField("phoneKey.customerNumber");
            join.setForeignField("_id.customerNumber");
            join.setFilter(new Document("common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            queryDef.setJoin(join);

            assertThat(queryDef.getJoin()).isNotNull();
            assertThat(queryDef.getJoin().getCollection()).isEqualTo("identity");
            assertThat(queryDef.hasJoin()).isTrue();
        }

        @Test
        void shouldReturnFalseForQueryWithoutJoin() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("simple_query");
            queryDef.setCollection("identity");
            queryDef.setType("find");
            queryDef.setFilter(new Document("common.taxIdentificationNumber", "123-45-6789"));

            assertThat(queryDef.hasJoin()).isFalse();
        }
    }

    @Nested
    class UC1PhonePlusSsnLast4Tests {

        @Test
        void shouldDefineUC1QueryStructure() {
            // UC-1: Phone Number + Last 4 of SSN
            // Step 1: Find phone record by phone number
            // Step 2: Join to identity by customerNumber, filter by SSN last 4

            QueryDefinition uc1 = createUC1QueryDefinition();

            assertThat(uc1.getName()).isEqualTo("uc1_phone_ssn_last4");
            assertThat(uc1.getCollection()).isEqualTo("phone");
            assertThat(uc1.getFilter().getString("phoneKey.phoneNumber")).isEqualTo("${param:phoneNumber}");
            assertThat(uc1.hasJoin()).isTrue();
            assertThat(uc1.getJoin().getCollection()).isEqualTo("identity");
        }

        private QueryDefinition createUC1QueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc1_phone_ssn_last4");
            queryDef.setDescription("UC-1: Search by Phone Number and Last 4 of SSN");
            queryDef.setCollection("phone");
            queryDef.setType("find");
            queryDef.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            JoinDefinition join = new JoinDefinition();
            join.setCollection("identity");
            join.setLocalField("phoneKey.customerNumber");
            join.setForeignField("_id.customerNumber");
            join.setFilter(new Document("common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            queryDef.setJoin(join);
            return queryDef;
        }
    }

    @Nested
    class UC2PhonePlusSsnLast4PlusAccountLast4Tests {

        @Test
        void shouldDefineUC2QueryStructure() {
            // UC-2: Phone + SSN Last 4 + Account Last 4
            // Step 1: Find phone record by phone number
            // Step 2: Join to identity by customerNumber, filter by SSN last 4
            // Step 3: Join to account by customerNumber, filter by account last 4

            QueryDefinition uc2 = createUC2QueryDefinition();

            assertThat(uc2.getName()).isEqualTo("uc2_phone_ssn_account");
            assertThat(uc2.getCollection()).isEqualTo("phone");
            assertThat(uc2.hasJoin()).isTrue();

            // First join: phone -> identity
            JoinDefinition firstJoin = uc2.getJoin();
            assertThat(firstJoin.getCollection()).isEqualTo("identity");
            assertThat(firstJoin.getFilter().getString("common.taxIdentificationNumberLast4"))
                .isEqualTo("${param:ssnLast4}");

            // Second join: identity -> account (chained)
            assertThat(firstJoin.getNextJoin()).isNotNull();
            JoinDefinition secondJoin = firstJoin.getNextJoin();
            assertThat(secondJoin.getCollection()).isEqualTo("account");
            assertThat(secondJoin.getFilter().getString("accountKey.accountNumberLast4"))
                .isEqualTo("${param:accountLast4}");
        }

        private QueryDefinition createUC2QueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc2_phone_ssn_account");
            queryDef.setDescription("UC-2: Search by Phone + SSN Last 4 + Account Last 4");
            queryDef.setCollection("phone");
            queryDef.setType("find");
            queryDef.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            // First join: phone -> identity
            JoinDefinition identityJoin = new JoinDefinition();
            identityJoin.setCollection("identity");
            identityJoin.setLocalField("phoneKey.customerNumber");
            identityJoin.setForeignField("_id.customerNumber");
            identityJoin.setFilter(new Document("common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            // Second join: identity -> account
            JoinDefinition accountJoin = new JoinDefinition();
            accountJoin.setCollection("account");
            accountJoin.setLocalField("_id.customerNumber");
            accountJoin.setForeignField("accountHolders.customerNumber");
            accountJoin.setFilter(new Document("accountKey.accountNumberLast4", "${param:accountLast4}"));

            identityJoin.setNextJoin(accountJoin);
            queryDef.setJoin(identityJoin);

            return queryDef;
        }
    }

    @Nested
    class UC3PhonePlusAccountLast4Tests {

        @Test
        void shouldDefineUC3QueryStructure() {
            // UC-3: Phone + Account Last 4
            // Step 1: Find phone record by phone number
            // Step 2: Join to identity by customerNumber (no filter)
            // Step 3: Join to account by customerNumber, filter by account last 4

            QueryDefinition uc3 = createUC3QueryDefinition();

            assertThat(uc3.getName()).isEqualTo("uc3_phone_account");
            assertThat(uc3.getCollection()).isEqualTo("phone");
            assertThat(uc3.hasJoin()).isTrue();

            // First join has no filter (just links phone to identity)
            JoinDefinition firstJoin = uc3.getJoin();
            assertThat(firstJoin.getCollection()).isEqualTo("identity");
            assertThat(firstJoin.getFilter()).isNull();

            // Second join has account last 4 filter
            JoinDefinition secondJoin = firstJoin.getNextJoin();
            assertThat(secondJoin.getCollection()).isEqualTo("account");
            assertThat(secondJoin.getFilter().getString("accountKey.accountNumberLast4"))
                .isEqualTo("${param:accountLast4}");
        }

        private QueryDefinition createUC3QueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc3_phone_account");
            queryDef.setDescription("UC-3: Search by Phone + Account Last 4");
            queryDef.setCollection("phone");
            queryDef.setType("find");
            queryDef.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            // First join: phone -> identity (no additional filter)
            JoinDefinition identityJoin = new JoinDefinition();
            identityJoin.setCollection("identity");
            identityJoin.setLocalField("phoneKey.customerNumber");
            identityJoin.setForeignField("_id.customerNumber");
            // No filter - just link to identity

            // Second join: identity -> account
            JoinDefinition accountJoin = new JoinDefinition();
            accountJoin.setCollection("account");
            accountJoin.setLocalField("_id.customerNumber");
            accountJoin.setForeignField("accountHolders.customerNumber");
            accountJoin.setFilter(new Document("accountKey.accountNumberLast4", "${param:accountLast4}"));

            identityJoin.setNextJoin(accountJoin);
            queryDef.setJoin(identityJoin);

            return queryDef;
        }
    }

    @Nested
    class UC4AccountPlusSsnLast4Tests {

        @Test
        void shouldDefineUC4QueryStructure() {
            // UC-4: Account Number + Last 4 of SSN
            // Step 1: Find account by account number
            // Step 2: Join to identity via accountHolders, filter by SSN last 4

            QueryDefinition uc4 = createUC4QueryDefinition();

            assertThat(uc4.getName()).isEqualTo("uc4_account_ssn");
            assertThat(uc4.getCollection()).isEqualTo("account");
            assertThat(uc4.getFilter().getString("accountKey.accountNumber")).isEqualTo("${param:accountNumber}");
            assertThat(uc4.hasJoin()).isTrue();

            JoinDefinition join = uc4.getJoin();
            assertThat(join.getCollection()).isEqualTo("identity");
            assertThat(join.getLocalField()).isEqualTo("accountHolders.customerNumber");
            assertThat(join.getForeignField()).isEqualTo("_id.customerNumber");
            assertThat(join.getFilter().getString("common.taxIdentificationNumberLast4"))
                .isEqualTo("${param:ssnLast4}");
        }

        private QueryDefinition createUC4QueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc4_account_ssn");
            queryDef.setDescription("UC-4: Search by Account Number and Last 4 of SSN");
            queryDef.setCollection("account");
            queryDef.setType("find");
            queryDef.setFilter(new Document("accountKey.accountNumber", "${param:accountNumber}"));

            JoinDefinition join = new JoinDefinition();
            join.setCollection("identity");
            join.setLocalField("accountHolders.customerNumber");
            join.setForeignField("_id.customerNumber");
            join.setFilter(new Document("common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            queryDef.setJoin(join);
            return queryDef;
        }
    }

    @Nested
    class UC5AddressPlusSsnPlusAccountTests {

        @Test
        void shouldDefineUC5QueryStructure() {
            // UC-5: City/State/ZIP + Last 4 SSN + Last 4 Account
            // Step 1: Find address by city/state/zip
            // Step 2: Join to identity by customerNumber, filter by SSN last 4
            // Step 3: Join to account by customerNumber, filter by account last 4

            QueryDefinition uc5 = createUC5QueryDefinition();

            assertThat(uc5.getName()).isEqualTo("uc5_address_ssn_account");
            assertThat(uc5.getCollection()).isEqualTo("address");
            assertThat(uc5.hasJoin()).isTrue();

            // First join: address -> identity with SSN filter
            JoinDefinition firstJoin = uc5.getJoin();
            assertThat(firstJoin.getCollection()).isEqualTo("identity");
            assertThat(firstJoin.getFilter().getString("common.taxIdentificationNumberLast4"))
                .isEqualTo("${param:ssnLast4}");

            // Second join: identity -> account with account last 4 filter
            JoinDefinition secondJoin = firstJoin.getNextJoin();
            assertThat(secondJoin).isNotNull();
            assertThat(secondJoin.getCollection()).isEqualTo("account");
            assertThat(secondJoin.getFilter().getString("accountKey.accountNumberLast4"))
                .isEqualTo("${param:accountLast4}");
        }

        private QueryDefinition createUC5QueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc5_address_ssn_account");
            queryDef.setDescription("UC-5: Search by City/State/ZIP + SSN Last 4 + Account Last 4");
            queryDef.setCollection("address");
            queryDef.setType("find");
            queryDef.setFilter(new Document()
                .append("addresses.stateCode", "${param:state}")
                .append("addresses.cityName", "${param:city}")
                .append("addresses.postalCode", "${param:zip}"));

            // First join: address -> identity
            JoinDefinition identityJoin = new JoinDefinition();
            identityJoin.setCollection("identity");
            identityJoin.setLocalField("_id.customerNumber");
            identityJoin.setForeignField("_id.customerNumber");
            identityJoin.setFilter(new Document("common.taxIdentificationNumberLast4", "${param:ssnLast4}"));

            // Second join: identity -> account
            JoinDefinition accountJoin = new JoinDefinition();
            accountJoin.setCollection("account");
            accountJoin.setLocalField("_id.customerNumber");
            accountJoin.setForeignField("accountHolders.customerNumber");
            accountJoin.setFilter(new Document("accountKey.accountNumberLast4", "${param:accountLast4}"));

            identityJoin.setNextJoin(accountJoin);
            queryDef.setJoin(identityJoin);

            return queryDef;
        }
    }

    @Nested
    class UC6EmailPlusAccountLast4Tests {

        @Test
        void shouldDefineUC6QueryStructure() {
            // UC-6: Email Address + Account Last 4
            // Step 1: Find identity by email
            // Step 2: Join to account by customerNumber, filter by account last 4

            QueryDefinition uc6 = createUC6QueryDefinition();

            assertThat(uc6.getName()).isEqualTo("uc6_email_account");
            assertThat(uc6.getCollection()).isEqualTo("identity");
            assertThat(uc6.getFilter().getString("emails.emailAddress")).isEqualTo("${param:email}");
            assertThat(uc6.hasJoin()).isTrue();

            JoinDefinition join = uc6.getJoin();
            assertThat(join.getCollection()).isEqualTo("account");
            assertThat(join.getLocalField()).isEqualTo("_id.customerNumber");
            assertThat(join.getForeignField()).isEqualTo("accountHolders.customerNumber");
            assertThat(join.getFilter().getString("accountKey.accountNumberLast4"))
                .isEqualTo("${param:accountLast4}");
        }

        private QueryDefinition createUC6QueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc6_email_account");
            queryDef.setDescription("UC-6: Search by Email and Account Last 4");
            queryDef.setCollection("identity");
            queryDef.setType("find");
            queryDef.setFilter(new Document("emails.emailAddress", "${param:email}"));

            JoinDefinition join = new JoinDefinition();
            join.setCollection("account");
            join.setLocalField("_id.customerNumber");
            join.setForeignField("accountHolders.customerNumber");
            join.setFilter(new Document("accountKey.accountNumberLast4", "${param:accountLast4}"));

            queryDef.setJoin(join);
            return queryDef;
        }
    }

    @Nested
    class UC7EmailPlusPhonePlusAccountTests {

        @Test
        void shouldDefineUC7QueryStructure() {
            // UC-7: Email + Phone + Account Last 4
            // Step 1: Find identity by email
            // Step 2: Join to phone by customerNumber, filter by phone number
            // Step 3: Join to account by customerNumber, filter by account last 4

            QueryDefinition uc7 = createUC7QueryDefinition();

            assertThat(uc7.getName()).isEqualTo("uc7_email_phone_account");
            assertThat(uc7.getCollection()).isEqualTo("identity");
            assertThat(uc7.getFilter().getString("emails.emailAddress")).isEqualTo("${param:email}");
            assertThat(uc7.hasJoin()).isTrue();

            // First join: identity -> phone
            JoinDefinition phoneJoin = uc7.getJoin();
            assertThat(phoneJoin.getCollection()).isEqualTo("phone");
            assertThat(phoneJoin.getFilter().getString("phoneKey.phoneNumber"))
                .isEqualTo("${param:phoneNumber}");

            // Second join: identity -> account
            JoinDefinition accountJoin = phoneJoin.getNextJoin();
            assertThat(accountJoin).isNotNull();
            assertThat(accountJoin.getCollection()).isEqualTo("account");
            assertThat(accountJoin.getFilter().getString("accountKey.accountNumberLast4"))
                .isEqualTo("${param:accountLast4}");
        }

        private QueryDefinition createUC7QueryDefinition() {
            QueryDefinition queryDef = new QueryDefinition();
            queryDef.setName("uc7_email_phone_account");
            queryDef.setDescription("UC-7: Search by Email + Phone + Account Last 4");
            queryDef.setCollection("identity");
            queryDef.setType("find");
            queryDef.setFilter(new Document("emails.emailAddress", "${param:email}"));

            // First join: identity -> phone
            JoinDefinition phoneJoin = new JoinDefinition();
            phoneJoin.setCollection("phone");
            phoneJoin.setLocalField("_id.customerNumber");
            phoneJoin.setForeignField("phoneKey.customerNumber");
            phoneJoin.setFilter(new Document("phoneKey.phoneNumber", "${param:phoneNumber}"));

            // Second join: identity -> account
            JoinDefinition accountJoin = new JoinDefinition();
            accountJoin.setCollection("account");
            accountJoin.setLocalField("_id.customerNumber");
            accountJoin.setForeignField("accountHolders.customerNumber");
            accountJoin.setFilter(new Document("accountKey.accountNumberLast4", "${param:accountLast4}"));

            phoneJoin.setNextJoin(accountJoin);
            queryDef.setJoin(phoneJoin);

            return queryDef;
        }
    }

    @Nested
    class YamlParsingTests {

        @Test
        void shouldParseJoinFromYamlMap() {
            // Simulate YAML structure for a join
            Map<String, Object> joinMap = Map.of(
                "collection", "identity",
                "localField", "phoneKey.customerNumber",
                "foreignField", "_id.customerNumber",
                "filter", Map.of("common.taxIdentificationNumberLast4", "${param:ssnLast4}")
            );

            JoinDefinition join = JoinDefinition.fromMap(joinMap);

            assertThat(join.getCollection()).isEqualTo("identity");
            assertThat(join.getLocalField()).isEqualTo("phoneKey.customerNumber");
            assertThat(join.getForeignField()).isEqualTo("_id.customerNumber");
            assertThat(join.getFilter()).isNotNull();
        }

        @Test
        void shouldParseChainedJoinsFromYamlMap() {
            // Simulate YAML structure with chained joins
            Map<String, Object> secondJoinMap = Map.of(
                "collection", "account",
                "localField", "_id.customerNumber",
                "foreignField", "accountHolders.customerNumber",
                "filter", Map.of("accountKey.accountNumberLast4", "${param:accountLast4}")
            );

            Map<String, Object> firstJoinMap = Map.of(
                "collection", "identity",
                "localField", "phoneKey.customerNumber",
                "foreignField", "_id.customerNumber",
                "filter", Map.of("common.taxIdentificationNumberLast4", "${param:ssnLast4}"),
                "join", secondJoinMap
            );

            JoinDefinition join = JoinDefinition.fromMap(firstJoinMap);

            assertThat(join.getCollection()).isEqualTo("identity");
            assertThat(join.getNextJoin()).isNotNull();
            assertThat(join.getNextJoin().getCollection()).isEqualTo("account");
        }
    }
}
