package com.wf.benchmark.generator;

import net.datafaker.Faker;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Provides random data generation using DataFaker.
 * Thread-safe implementation using ThreadLocalRandom.
 */
public class RandomDataProvider {

    private static final ThreadLocal<Faker> FAKER = ThreadLocal.withInitial(Faker::new);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    // US State codes with weights based on population
    private static final List<StateWeight> US_STATES = List.of(
        new StateWeight("CA", 12.0), new StateWeight("TX", 9.0), new StateWeight("FL", 7.0),
        new StateWeight("NY", 6.0), new StateWeight("PA", 4.0), new StateWeight("IL", 4.0),
        new StateWeight("OH", 3.5), new StateWeight("GA", 3.2), new StateWeight("NC", 3.1),
        new StateWeight("MI", 3.0), new StateWeight("NJ", 2.8), new StateWeight("VA", 2.6),
        new StateWeight("WA", 2.3), new StateWeight("AZ", 2.2), new StateWeight("MA", 2.1),
        new StateWeight("TN", 2.0), new StateWeight("IN", 2.0), new StateWeight("MO", 1.8),
        new StateWeight("MD", 1.8), new StateWeight("WI", 1.7), new StateWeight("CO", 1.7),
        new StateWeight("MN", 1.7), new StateWeight("SC", 1.5), new StateWeight("AL", 1.5),
        new StateWeight("LA", 1.4), new StateWeight("KY", 1.3), new StateWeight("OR", 1.2),
        new StateWeight("OK", 1.2), new StateWeight("CT", 1.1), new StateWeight("UT", 1.0),
        new StateWeight("IA", 1.0), new StateWeight("NV", 0.9), new StateWeight("AR", 0.9),
        new StateWeight("MS", 0.9), new StateWeight("KS", 0.9), new StateWeight("NM", 0.6),
        new StateWeight("NE", 0.6), new StateWeight("ID", 0.6), new StateWeight("WV", 0.5),
        new StateWeight("HI", 0.4), new StateWeight("NH", 0.4), new StateWeight("ME", 0.4),
        new StateWeight("MT", 0.3), new StateWeight("RI", 0.3), new StateWeight("DE", 0.3),
        new StateWeight("SD", 0.3), new StateWeight("ND", 0.2), new StateWeight("AK", 0.2),
        new StateWeight("VT", 0.2), new StateWeight("WY", 0.2)
    );

    // Canadian provinces
    private static final List<StateWeight> CA_PROVINCES = List.of(
        new StateWeight("ON", 38.0), new StateWeight("QC", 23.0), new StateWeight("BC", 13.5),
        new StateWeight("AB", 11.5), new StateWeight("MB", 3.6), new StateWeight("SK", 3.0),
        new StateWeight("NS", 2.6), new StateWeight("NB", 2.0), new StateWeight("NL", 1.3),
        new StateWeight("PE", 0.4)
    );

    // Phone providers
    private static final List<String> MOBILE_PROVIDERS = List.of(
        "Verizon Wireless", "AT&T Mobility", "T-Mobile", "US Cellular", "Cricket Wireless",
        "Metro by T-Mobile", "Boost Mobile", "Visible", "Mint Mobile", "Google Fi"
    );

    private static final List<String> LANDLINE_PROVIDERS = List.of(
        "AT&T", "Verizon", "CenturyLink", "Frontier", "Windstream",
        "Comcast Business", "Spectrum Business", "Cox Business"
    );

    // Address use codes
    private static final List<String> ADDRESS_USE_CODES = List.of(
        "CUSTOMER_RESIDENCE", "STATEMENT_ADDRESS", "BILLING_ADDRESS", "MAILING_ADDRESS"
    );

    // Phone type codes
    private static final List<String> PHONE_TYPE_CODES = List.of(
        "MOBILE", "HOME", "BUSINESS", "FAX"
    );

    // Corporation types
    private static final List<String> CORP_TYPES = List.of(
        "C_CORP", "S_CORP", "LLC", "PARTNERSHIP", "SOLE_PROPRIETORSHIP"
    );

    // Business entity types
    private static final List<String> BUSINESS_ENTITY_TYPES = List.of(
        "CORPORATION", "LIMITED_LIABILITY_COMPANY", "PARTNERSHIP", "NON_PROFIT"
    );

    // ID types
    private static final List<String> ID_TYPES = List.of(
        "DRIVERS_LICENSE", "PASSPORT", "STATE_ID", "MILITARY_ID"
    );

    private record StateWeight(String code, double weight) {}

    public Faker faker() {
        return FAKER.get();
    }

    public Random random() {
        return ThreadLocalRandom.current();
    }

    public String firstName() {
        return faker().name().firstName().toUpperCase();
    }

    public String lastName() {
        return faker().name().lastName().toUpperCase();
    }

    public String middleName() {
        return faker().name().firstName().toUpperCase();
    }

    public String fullName() {
        return firstName() + " " + middleName() + " " + lastName();
    }

    public String companyName() {
        return "*" + faker().company().name().toUpperCase();
    }

    public String ssn() {
        return String.format("%03d%02d%04d",
            random().nextInt(900) + 100,
            random().nextInt(99) + 1,
            random().nextInt(9999) + 1);
    }

    public String ein() {
        return String.format("%02d%07d",
            random().nextInt(99) + 1,
            random().nextInt(9999999) + 1);
    }

    public String itin() {
        return String.format("9%02d%02d%04d",
            random().nextInt(99),
            random().nextInt(99),
            random().nextInt(9999));
    }

    public String birthDate(int minAge, int maxAge) {
        int age = random().nextInt(maxAge - minAge + 1) + minAge;
        LocalDate date = LocalDate.now().minusYears(age).minusDays(random().nextInt(365));
        return date.format(DATE_FORMATTER);
    }

    public String birthDateMask(String birthDate) {
        return birthDate.substring(0, 6);
    }

    public Date timestamp() {
        long now = System.currentTimeMillis();
        long twoYearsAgo = now - (2L * 365 * 24 * 60 * 60 * 1000);
        return new Date(twoYearsAgo + (long) (random().nextDouble() * (now - twoYearsAgo)));
    }

    public Date recentTimestamp() {
        long now = System.currentTimeMillis();
        long threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000);
        return new Date(threeMonthsAgo + (long) (random().nextDouble() * (now - threeMonthsAgo)));
    }

    public String isoDate(Date date) {
        return DateTimeFormatter.ISO_INSTANT.format(date.toInstant());
    }

    public String dateString(Date date) {
        return LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE);
    }

    public String streetAddress() {
        return faker().address().streetAddress();
    }

    public String secondaryAddress() {
        return random().nextDouble() < 0.3 ? faker().address().secondaryAddress() : null;
    }

    public String city() {
        return faker().address().city();
    }

    public String usState() {
        return weightedChoice(US_STATES);
    }

    public String caProvince() {
        return weightedChoice(CA_PROVINCES);
    }

    public String postalCode(String countryCode, String stateCode) {
        if ("CA".equals(countryCode)) {
            return faker().address().zipCode().substring(0, 3) + " " +
                faker().address().zipCode().substring(0, 3);
        }
        return faker().address().zipCode();
    }

    public String country() {
        double rand = random().nextDouble();
        if (rand < 0.85) return "US";
        if (rand < 0.95) return "CA";
        return "MX";
    }

    public String stateForCountry(String countryCode) {
        return switch (countryCode) {
            case "US" -> usState();
            case "CA" -> caProvince();
            case "MX" -> randomChoice(List.of("AGS", "BCN", "BCS", "CAM", "CHP", "CHH", "COA", "COL", "DUR", "GUA"));
            default -> "XX";
        };
    }

    public String addressUseCode() {
        return randomChoice(ADDRESS_USE_CODES);
    }

    public String phoneNumber() {
        return String.format("%03d%03d%04d",
            random().nextInt(800) + 200,
            random().nextInt(900) + 100,
            random().nextInt(10000));
    }

    public String phoneTypeCode() {
        return randomChoice(PHONE_TYPE_CODES);
    }

    public String lineTypeCode(String phoneType) {
        return "MOBILE".equals(phoneType) ? "WIRELESS" : "LANDLINE";
    }

    public String phoneProvider(String lineType) {
        return "WIRELESS".equals(lineType) ?
            randomChoice(MOBILE_PROVIDERS) : randomChoice(LANDLINE_PROVIDERS);
    }

    public String extension() {
        return random().nextDouble() < 0.1 ? String.valueOf(random().nextInt(9000) + 100) : null;
    }

    public String corporationType() {
        return randomChoice(CORP_TYPES);
    }

    public String businessEntityType() {
        return randomChoice(BUSINESS_ENTITY_TYPES);
    }

    public String naicsCode() {
        return String.valueOf(100000 + random().nextInt(900000));
    }

    public String idType() {
        return randomChoice(ID_TYPES);
    }

    public String idNumber(String idType) {
        return switch (idType) {
            case "DRIVERS_LICENSE" -> faker().regexify("[A-Z][0-9]{7}");
            case "PASSPORT" -> faker().regexify("[A-Z][0-9]{8}");
            case "STATE_ID" -> faker().regexify("[0-9]{9}");
            case "MILITARY_ID" -> faker().regexify("[0-9]{10}");
            default -> faker().regexify("[A-Z0-9]{10}");
        };
    }

    public String processName() {
        int processNum = random().nextInt(99999);
        String suffix = random().nextDouble() < 0.7 ? "CDC" : "IL";
        return String.format("%05d - %s", processNum, suffix);
    }

    public String cdcIdentifier(long customerNumber, int sequence) {
        return String.format("CDC_%d_%03d", customerNumber, sequence);
    }

    public int randomInt(int min, int max) {
        return random().nextInt(max - min + 1) + min;
    }

    public double randomDouble() {
        return random().nextDouble();
    }

    public boolean randomBoolean() {
        return random().nextBoolean();
    }

    public boolean randomBoolean(double trueProbability) {
        return random().nextDouble() < trueProbability;
    }

    public <T> T randomChoice(List<T> choices) {
        return choices.get(random().nextInt(choices.size()));
    }

    private String weightedChoice(List<StateWeight> weights) {
        double totalWeight = weights.stream().mapToDouble(StateWeight::weight).sum();
        double rand = random().nextDouble() * totalWeight;
        double cumulative = 0;
        for (StateWeight sw : weights) {
            cumulative += sw.weight();
            if (rand <= cumulative) {
                return sw.code();
            }
        }
        return weights.getLast().code();
    }
}
