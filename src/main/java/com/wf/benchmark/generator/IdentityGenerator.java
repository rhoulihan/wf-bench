package com.wf.benchmark.generator;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Generates identity documents matching the sample schema.
 */
public class IdentityGenerator implements DataGenerator {

    private static final long BASE_CUSTOMER_NUMBER = 1_000_000_001L;

    private final RandomDataProvider random;
    private final double individualRatio;
    private final String collectionName;

    public IdentityGenerator(RandomDataProvider random, double individualRatio, String collectionPrefix) {
        this.random = random;
        this.individualRatio = individualRatio;
        this.collectionName = collectionPrefix + "identity";
    }

    @Override
    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public Document generate(long sequenceNumber) {
        long customerNumber = BASE_CUSTOMER_NUMBER + sequenceNumber;
        int customerCompanyNumber = random.randomInt(1, 3);
        boolean isIndividual = random.randomBoolean(individualRatio);

        Document doc = new Document();

        // _id (composite key)
        doc.append("_id", new Document()
            .append("customerNumber", customerNumber)
            .append("customerCompanyNumber", customerCompanyNumber));

        // common section
        Document common = generateCommon(customerNumber, isIndividual);
        doc.append("common", common);

        // individual or nonIndividual section
        if (isIndividual) {
            doc.append("individual", generateIndividual());
        } else {
            doc.append("nonIndividual", generateNonIndividual());
        }

        // metaData
        doc.append("metaData", generateMetaData(customerNumber, isIndividual));

        return doc;
    }

    private Document generateCommon(long customerNumber, boolean isIndividual) {
        Document common = new Document();

        String fullName;
        String taxIdType;
        String taxIdNumber;

        if (isIndividual) {
            fullName = random.fullName();
            double taxTypeRand = random.randomDouble();
            if (taxTypeRand < 0.85) {
                taxIdType = "SSN";
                taxIdNumber = random.ssn();
            } else {
                taxIdType = "ITIN";
                taxIdNumber = random.itin();
            }
        } else {
            fullName = random.companyName();
            taxIdType = "EIN";
            taxIdNumber = random.ein();
        }

        common.append("fullName", fullName);
        common.append("entityTypeIndicator", isIndividual ? "INDIVIDUAL" : "NON_INDIVIDUAL");
        common.append("taxIdentificationTypeCode", taxIdType);
        common.append("taxIdentificationNumber", taxIdNumber);

        // identifications array (for individuals)
        if (isIndividual && random.randomBoolean(0.8)) {
            common.append("identifications", generateIdentifications(customerNumber));
        }

        // systemOfRecord
        common.append("systemOfRecord", new Document()
            .append("M_CIF_NAME_1", fullName)
            .append("M_CIF_NAME_2", "")
            .append("M_CIF_SSN_TAXID_IND", isIndividual ? "0" : "1"));

        return common;
    }

    private List<Document> generateIdentifications(long customerNumber) {
        List<Document> identifications = new ArrayList<>();
        int count = random.randomInt(1, 2);

        for (int i = 0; i < count; i++) {
            String idType = random.idType();
            String subTypeCode = idType.equals("DRIVERS_LICENSE") ? "DL" : "";
            String issuerCountry = random.country();
            Date lastMaintDate = random.recentTimestamp();

            Document id = new Document()
                .append("occurrenceNumber", i + 1)
                .append("lastMaintenanceDate", random.dateString(lastMaintDate))
                .append("subTypeCode", subTypeCode)
                .append("metaData", new Document()
                    .append("createdByProcessName", "24274 - IL")
                    .append("createdByIdentifier", "VT_IL")
                    .append("createdTimestamp", random.timestamp())
                    .append("updatedByProcessName", "24274 - CDC")
                    .append("updatedByIdentifier", "VT_CDC")
                    .append("updatedTimestamp", random.recentTimestamp())
                    .append("cdcDeletedFlag", "N")
                    .append("dg24274", new Document()
                        .append("createdCdcIdentifier", random.cdcIdentifier(customerNumber, 1))
                        .append("createdCdcTimestamp", random.timestamp().getTime())
                        .append("updatedCdcIdentifier", random.cdcIdentifier(customerNumber, random.randomInt(10, 99)))
                        .append("updatedCdcTimestamp", random.recentTimestamp().getTime())))
                .append("typeCode", idType)
                .append("issuerCountryCode", issuerCountry)
                .append("identificationNumber", random.idNumber(idType))
                .append("expirationDate", generateFutureDate(1, 10))
                .append("issueDate", generatePastDate(1, 10))
                .append("issuerStateCode", "US".equals(issuerCountry) ? random.usState() : "")
                .append("systemOfRecord", new Document()
                    .append("M_CIF_ID_TYPE_CD", idType.substring(0, 4).toUpperCase())
                    .append("M_CIF_ID_SUB_TYPE", subTypeCode));

            identifications.add(id);
        }

        return identifications;
    }

    private Document generateIndividual() {
        String birthDate = random.birthDate(18, 85);
        String country = random.country();
        String nationalityCountry = random.randomBoolean(0.9) ? "US" : random.country();

        return new Document()
            .append("birthDate", birthDate)
            .append("birthDateMask", random.birthDateMask(birthDate))
            .append("namePrefix", random.randomChoice(List.of("Mr", "Ms", "Mrs", "Dr", "")))
            .append("middleName", random.middleName())
            .append("nameSuffix", random.randomBoolean(0.05) ? random.randomChoice(List.of("Jr", "Sr", "III")) : "")
            .append("lastName", random.lastName())
            .append("systemOfRecord", new Document()
                .append("M_CIF_PERM_US_RESIDENT", random.randomBoolean(0.9) ? "Y" : ""))
            .append("firstName", random.firstName())
            .append("permanentResidenceCountryCode", country)
            .append("nationalityCountryCode", nationalityCountry)
            .append("generationalSuffix", "");
    }

    private Document generateNonIndividual() {
        String corpType = random.corporationType();
        String entityType = random.businessEntityType();
        boolean isPublic = random.randomBoolean(0.1);
        boolean isNonProfit = entityType.equals("NON_PROFIT") || random.randomBoolean(0.05);
        String registrationState = random.usState();
        String registrationCountry = random.randomBoolean(0.95) ? "US" : "CA";

        Document nonIndividual = new Document()
            .append("isPubliclyTraded", isPublic)
            .append("corporationTypeCode", corpType)
            .append("isLegalEntity", true)
            .append("registrationStateCode", registrationState)
            .append("registrationCountryCode", registrationCountry)
            .append("businessEntitySubTypeCode", corpType)
            .append("businessEstablishedDate", generatePastDate(1, 50))
            .append("isNonprofit", isNonProfit)
            .append("businessDescriptionText", random.faker().company().industry().toUpperCase())
            .append("businessEntityTypeCode", entityType);

        // NAICS codes
        List<Document> naicsCodes = new ArrayList<>();
        int naicsCount = random.randomInt(1, 3);
        for (int i = 0; i < naicsCount; i++) {
            naicsCodes.add(new Document()
                .append("occurrenceNumber", i + 1)
                .append("naicsCode", random.naicsCode()));
        }
        nonIndividual.append("naicsCodes", naicsCodes);

        return nonIndividual;
    }

    private Document generateMetaData(long customerNumber, boolean isIndividual) {
        Date createdTs = random.timestamp();
        Date updatedTs = random.recentTimestamp();
        int cdcSeq = random.randomInt(50, 99);

        Document metaData = new Document()
            .append("createdByProcessName", "24201 - IL")
            .append("createdByIdentifier", "VT_IL")
            .append("createdTimestamp", createdTs)
            .append("updatedByProcessName", "24201 - CDC")
            .append("updatedByIdentifier", "VT_CDC")
            .append("updatedTimestamp", updatedTs)
            .append("cdcDeletedFlag", "N")
            .append("dg24201CD", new Document()
                .append("createdCdcIdentifier", random.cdcIdentifier(customerNumber, 1))
                .append("createdCdcTimestamp", createdTs.getTime())
                .append("updatedCdcIdentifier", random.cdcIdentifier(customerNumber, cdcSeq))
                .append("updatedCdcTimestamp", updatedTs.getTime())
                .append("cdcDeletedFlag", "N"));

        if (isIndividual) {
            metaData.append("dg24202cd", new Document()
                .append("createdCdcIdentifier", random.cdcIdentifier(customerNumber, 1))
                .append("createdCdcTimestamp", createdTs.getTime())
                .append("updatedCdcIdentifier", random.cdcIdentifier(customerNumber, cdcSeq))
                .append("updatedCdcTimestamp", updatedTs.getTime())
                .append("cdcDeletedFlag", "N"));
        } else {
            metaData.append("dg24203", new Document()
                .append("createdCdcIdentifier", random.cdcIdentifier(customerNumber, 1))
                .append("createdCdcTimestamp", createdTs.getTime())
                .append("updatedCdcIdentifier", random.cdcIdentifier(customerNumber, cdcSeq))
                .append("updatedCdcTimestamp", updatedTs.getTime())
                .append("cdcDeletedFlag", "N"));
            metaData.append("dg75640248", new Document()
                .append("createdCdcIdentifier", random.cdcIdentifier(customerNumber, 1))
                .append("createdCdcTimestamp", createdTs.getTime())
                .append("updatedCdcIdentifier", random.cdcIdentifier(customerNumber, cdcSeq))
                .append("updatedCdcTimestamp", updatedTs.getTime())
                .append("cdcDeletedFlag", "N"));
        }

        return metaData;
    }

    private String generateFutureDate(int minYears, int maxYears) {
        int years = random.randomInt(minYears, maxYears);
        int days = random.randomInt(0, 365);
        return java.time.LocalDate.now().plusYears(years).plusDays(days)
            .format(java.time.format.DateTimeFormatter.ISO_DATE);
    }

    private String generatePastDate(int minYears, int maxYears) {
        int years = random.randomInt(minYears, maxYears);
        int days = random.randomInt(0, 365);
        return java.time.LocalDate.now().minusYears(years).minusDays(days)
            .format(java.time.format.DateTimeFormatter.ISO_DATE);
    }
}
