# Query Test Implementation Plan

## Document Analysis Summary

Based on the Wells Fargo Vector Search Solution Demonstration requirements, this plan outlines the query tests, index strategy, and data model enhancements needed for the benchmark tool.

---

## 1. Extracted Use Cases from PDF

### 1.1 Primary Demonstration Use Cases (Live Demo Required)

| Use Case | Search Criteria | Collections Involved |
|----------|-----------------|---------------------|
| UC-1 | Phone Number + Last 4 of SSN | Phone, Identity |
| UC-2 | Phone Number + Last 4 of SSN + Last 4 of Account # | Phone, Identity, Account |
| UC-3 | Phone Number + Last 4 of Account # | Phone, Account |
| UC-4 | Last 4 of SSN + Last 4 of Account # | Identity, Account |
| UC-5 | City/State/ZIP + Last 4 of SSN + Last 4 of Account # | Address, Identity, Account |
| UC-6 | Email Address + Last 4 of Account # | Email, Account |
| UC-7 | Email Address + Phone Number + Last 4 of Account # | Email, Phone, Account |

### 1.2 Other Required Searches

| ID | Search Type | Description |
|----|-------------|-------------|
| OS-1 | TIN Full Search | Full 9-digit TIN/SSN search → Returns Customer |
| OS-2 | Account Number Search | Account # (with optional product type and COID) → Returns Customer |
| OS-3 | Tokenized Account Search | Tokenized Account Number → Returns Customer |
| OS-4 | Phone Full Search | Full 10-digit phone number → Returns Customer |

### 1.3 Written Response Search Capabilities

| ID | Category | Capability | Example |
|----|----------|------------|---------|
| WR-A | Address Search | Full Address with Standardization | "1234 Main Street" → "1234 Main St" |
| WR-B | Address Search | City + State + ZIP (with identifier) | "John Doe, Dallas, TX 75001" |
| WR-C | Address Search | ZIP Only (with identifier) | "Jane Smith, 90210" |
| WR-D | Address Search | Any Related Address | Customer linked via CS address |
| WR-E | Email | Exact Match | jane.doe@email.com |
| WR-F | DOB/EOD | Exact Match (with identifier) | "John Doe, DOB: 01/15/1980" |
| WR-G | Entity Type | Filter Individual/Non-Individual | "Acme Corp, Non-Individual" |
| WR-H | Name Search | Full Name (First, Middle, Last) | "John A. Doe" |
| WR-I | Name Search | Nickname Matching | "Peggy Smith" → "Margaret Smith" |
| WR-J | Name Search | Phonetic Matching | "Sallie Brown" → "Sally Brown" |
| WR-K | ECN Search | Full ECN | Returns Customer |
| WR-L | Vendor ID | Acxiom AbiliTEC ID | "A123456789" |
| WR-M | Name Matching | Fuzzy Matching | "Sandr Jones" → "Sandra Jones" |
| WR-N | QR Code | Search by QR code | App-based search |
| WR-O | Related Customers | Linked Profiles | "John Smith" → "Smith Enterprises" |
| WR-P | Name Matching | Sanitized Business Name | "Cory's Candy Corporation" → "Corys Candy Co" |
| WR-Q | TIN | Last 4 Digits (with identifier) | "Doe, 6789" → "John Doe" |
| WR-S | ID Documents | Driver's License/Passport | "DL12345678" |
| WR-T | Employment | Employer Filter (with identifier) | "Jane Smith, Employer: TechCorp" |
| WR-U | Segmentation | Segment Filter | "John Doe, Segment: Active Duty" |
| WR-V | Citizenship | Country Filter | "Jane Doe, Citizenship: Canada" |
| WR-W | LOB Owner | Line of Business Filter | "John Smith, LOB: Brokerage" |

---

## 2. Data Model Requirements

### 2.1 Current Collections
- `identity` - Customer identity information (enhanced with embedded emails)
- `address` - Customer addresses
- `phone` - Customer phone numbers

### 2.2 New Collections Required

#### Account Collection (NEW - Account to Customers)
**Key Design Decision**: Accounts can have multiple identity holders (joint accounts, business accounts, etc.)

```json
{
  "_id": ObjectId,
  "accountKey": {
    "accountNumber": String,
    "accountNumberLast4": String,  // Indexed for partial search
    "accountNumberTokenized": String
  },
  "productTypeCode": String,
  "companyOfInterestId": String,  // COID
  "accountStatus": String,
  "accountHolders": [              // Array of identity references
    {
      "customerNumber": Long,
      "customerCompanyNumber": Integer,
      "relationshipType": String,  // PRIMARY, JOINT, AUTHORIZED, BENEFICIARY
      "isPrimaryHolder": Boolean
    }
  ],
  "metaData": { ... }
}
```

### 2.3 Email Embedded in Identity (NOT a separate collection)
**Key Design Decision**: Emails are embedded as an array within the identity document for better query performance and data locality.

```json
// Within identity document:
{
  "_id": { "customerNumber": Long, "customerCompanyNumber": Integer },
  "common": { ... },
  "individual": { ... },
  "emails": [                      // Embedded array
    {
      "emailAddress": String,      // Indexed via multikey index
      "emailTypeCode": String,     // PRIMARY, SECONDARY, WORK
      "verificationStatus": String,
      "isPrimary": Boolean
    }
  ]
}
```

### 2.4 Identity Collection Enhancements
Add these fields if not present:
- `common.taxIdentificationNumberLast4` - Last 4 digits of TIN (indexed)
- `common.ecn` - Enterprise Customer Number
- `common.vendorIds.acxiomAbilitecId` - Acxiom ID
- `individual.birthDate` - DOB in searchable format
- `common.identifications[].documentNumber` - DL/Passport numbers
- `common.employerName` - Employer information
- `common.customerSegment` - Customer segment (Active Duty, etc.)
- `common.citizenshipCountryCode` - Citizenship
- `common.lineOfBusiness` - LOB owner

---

## 3. Index Strategy

### 3.1 Identity Collection Indexes

| Index Name | Keys | Purpose | Use Cases |
|------------|------|---------|-----------|
| `idx_identity_tin_last4` | `common.taxIdentificationNumberLast4: 1` | Partial SSN search | UC-1,2,4,5, WR-Q |
| `idx_identity_tin_full` | `common.taxIdentificationNumber: 1` | Full TIN search | OS-1 |
| `idx_identity_ecn` | `common.ecn: 1` | ECN lookup | WR-K |
| `idx_identity_fullname` | `common.fullName: 1` | Name search | WR-H |
| `idx_identity_name_parts` | `individual.lastName: 1, individual.firstName: 1` | Name part search | WR-H |
| `idx_identity_entity_type` | `common.entityTypeIndicator: 1` | Entity filter | WR-G |
| `idx_identity_dob` | `individual.birthDate: 1` | DOB search | WR-F |
| `idx_identity_dl_passport` | `common.identifications.documentNumber: 1` | ID document search | WR-S |
| `idx_identity_employer` | `common.employerName: 1` | Employer filter | WR-T |
| `idx_identity_segment` | `common.customerSegment: 1` | Segment filter | WR-U |
| `idx_identity_citizenship` | `common.citizenshipCountryCode: 1` | Citizenship filter | WR-V |
| `idx_identity_lob` | `common.lineOfBusiness: 1` | LOB filter | WR-W |
| `idx_identity_vendor_acxiom` | `common.vendorIds.acxiomAbilitecId: 1` | Vendor ID search | WR-L |
| `idx_identity_fullname_text` | `common.fullName: "text"` | Text/fuzzy search | WR-I, WR-J, WR-M |

### 3.2 Address Collection Indexes

| Index Name | Keys | Purpose | Use Cases |
|------------|------|---------|-----------|
| `idx_address_state_city_zip` | `addresses.stateCode: 1, addresses.cityName: 1, addresses.postalCode: 1` | Location search | UC-5, WR-B |
| `idx_address_zip` | `addresses.postalCode: 1` | ZIP-only search | WR-C |
| `idx_address_full` | `addresses.addressLines: 1, addresses.cityName: 1, addresses.stateCode: 1` | Full address | WR-A |
| `idx_address_customer` | `_id.customerNumber: 1, _id.customerCompanyNumber: 1` | Customer lookup | All |

### 3.3 Phone Collection Indexes

| Index Name | Keys | Purpose | Use Cases |
|------------|------|---------|-----------|
| `idx_phone_number_full` | `phoneKey.phoneNumber: 1` | Full phone search | OS-4 |
| `idx_phone_customer` | `phoneKey.customerNumber: 1, phoneKey.customerCompanyNumber: 1` | Customer lookup | UC-1,2,3,7 |

### 3.4 Email Indexes (Embedded in Identity - Multikey)

| Index Name | Keys | Purpose | Use Cases |
|------------|------|---------|-----------|
| `idx_identity_email` | `emails.emailAddress: 1` | Email exact match (multikey) | UC-6,7, WR-E |

> **Note**: Since emails are embedded in identity documents, MongoDB automatically uses multikey indexing to index each email address in the array. This eliminates the need for a separate collection and join operation.

### 3.5 Account Collection Indexes (NEW - Multiple Holders)

| Index Name | Keys | Purpose | Use Cases |
|------------|------|---------|-----------|
| `idx_account_last4` | `accountKey.accountNumberLast4: 1` | Partial account search | UC-2,3,4,5,6,7 |
| `idx_account_full` | `accountKey.accountNumber: 1` | Full account search | OS-2 |
| `idx_account_tokenized` | `accountKey.accountNumberTokenized: 1` | Tokenized search | OS-3 |
| `idx_account_holders` | `accountHolders.customerNumber: 1, accountHolders.customerCompanyNumber: 1` | Customer lookup (multikey) | All |
| `idx_account_product_coid` | `productTypeCode: 1, companyOfInterestId: 1` | Product/COID filter | OS-2 |

> **Note**: The `accountHolders` array supports multiple identities per account (joint accounts, authorized users, etc.). The multikey index enables efficient lookup of all accounts for a given customer.

---

## 4. Query Test Definitions

### 4.1 Primary Use Case Queries

#### UC-1: Phone + Last 4 SSN
```yaml
- name: "uc1_phone_ssn_last4"
  description: "Search by Phone Number and Last 4 of SSN"
  type: "multi_collection_join"
  steps:
    - collection: "phone"
      filter:
        phoneKey.phoneNumber: "${param:phoneNumber}"
      output: "customerNumbers"
    - collection: "identity"
      filter:
        _id.customerNumber: { $in: "${prev:customerNumbers}" }
        common.taxIdentificationNumberLast4: "${param:ssnLast4}"
  parameters:
    phoneNumber:
      type: "random_from_loaded"
    ssnLast4:
      type: "random_pattern"
      pattern: "[0-9]{4}"
```

#### UC-2: Phone + Last 4 SSN + Last 4 Account
```yaml
- name: "uc2_phone_ssn_account"
  description: "Search by Phone, SSN Last 4, and Account Last 4"
  type: "multi_collection_join"
  steps:
    - collection: "phone"
      filter:
        phoneKey.phoneNumber: "${param:phoneNumber}"
      output: "phoneCustomers"
    - collection: "account"
      filter:
        accountKey.accountNumberLast4: "${param:accountLast4}"
      output: "accountCustomers"
    - collection: "identity"
      filter:
        _id.customerNumber: { $in: "${intersect:phoneCustomers,accountCustomers}" }
        common.taxIdentificationNumberLast4: "${param:ssnLast4}"
```

#### UC-3: Phone + Last 4 Account
```yaml
- name: "uc3_phone_account"
  description: "Search by Phone and Account Last 4"
  type: "multi_collection_join"
  steps:
    - collection: "phone"
      filter:
        phoneKey.phoneNumber: "${param:phoneNumber}"
      output: "phoneCustomers"
    - collection: "account"
      filter:
        accountHolders.customerNumber: { $in: "${prev:phoneCustomers}" }
        accountKey.accountNumberLast4: "${param:accountLast4}"
```

#### UC-4: Last 4 SSN + Last 4 Account
```yaml
- name: "uc4_ssn_account"
  description: "Search by SSN Last 4 and Account Last 4"
  type: "multi_collection_join"
  steps:
    - collection: "identity"
      filter:
        common.taxIdentificationNumberLast4: "${param:ssnLast4}"
      output: "identityCustomers"
    - collection: "account"
      filter:
        accountHolders.customerNumber: { $in: "${prev:identityCustomers}" }
        accountKey.accountNumberLast4: "${param:accountLast4}"
```

#### UC-5: Address + Last 4 SSN + Last 4 Account
```yaml
- name: "uc5_address_ssn_account"
  description: "Search by City/State/ZIP, SSN Last 4, and Account Last 4"
  type: "multi_collection_join"
  steps:
    - collection: "address"
      filter:
        addresses.cityName: "${param:city}"
        addresses.stateCode: "${param:state}"
        addresses.postalCode: "${param:zip}"
      output: "addressCustomers"
    - collection: "identity"
      filter:
        _id.customerNumber: { $in: "${prev:addressCustomers}" }
        common.taxIdentificationNumberLast4: "${param:ssnLast4}"
      output: "identityCustomers"
    - collection: "account"
      filter:
        accountHolders.customerNumber: { $in: "${prev:identityCustomers}" }
        accountKey.accountNumberLast4: "${param:accountLast4}"
```

#### UC-6: Email + Last 4 Account
```yaml
- name: "uc6_email_account"
  description: "Search by Email and Account Last 4"
  type: "multi_collection_join"
  steps:
    - collection: "identity"       # Email is embedded in identity
      filter:
        emails.emailAddress: "${param:email}"
      output: "emailCustomers"
    - collection: "account"
      filter:
        accountHolders.customerNumber: { $in: "${prev:emailCustomers}" }
        accountKey.accountNumberLast4: "${param:accountLast4}"
```

#### UC-7: Email + Phone + Last 4 Account
```yaml
- name: "uc7_email_phone_account"
  description: "Search by Email, Phone, and Account Last 4"
  type: "multi_collection_join"
  steps:
    - collection: "identity"       # Email is embedded in identity
      filter:
        emails.emailAddress: "${param:email}"
      output: "emailCustomers"
    - collection: "phone"
      filter:
        phoneKey.phoneNumber: "${param:phoneNumber}"
      output: "phoneCustomers"
    - collection: "account"
      filter:
        accountHolders.customerNumber: { $in: "${intersect:emailCustomers,phoneCustomers}" }
        accountKey.accountNumberLast4: "${param:accountLast4}"
```

### 4.2 Other Search Queries

```yaml
# OS-1: Full TIN Search
- name: "os1_tin_full"
  description: "Search by full 9-digit TIN"
  collection: "identity"
  type: "find"
  filter:
    common.taxIdentificationNumber: "${param:tin}"
  expectedResults: 1

# OS-2: Account Number with Product/COID
- name: "os2_account_product_coid"
  description: "Search by Account Number with optional product type and COID"
  collection: "account"
  type: "find"
  filter:
    accountKey.accountNumber: "${param:accountNumber}"
    productTypeCode: "${param:productType}"
    companyOfInterestId: "${param:coid}"

# OS-3: Tokenized Account
- name: "os3_account_tokenized"
  description: "Search by tokenized account number"
  collection: "account"
  type: "find"
  filter:
    accountKey.accountNumberTokenized: "${param:tokenizedAccount}"

# OS-4: Full Phone Search
- name: "os4_phone_full"
  description: "Search by full 10-digit phone number"
  collection: "phone"
  type: "find"
  filter:
    phoneKey.phoneNumber: "${param:phoneNumber}"
```

### 4.3 Written Response Capability Queries

```yaml
# WR-E: Email Exact Match (embedded in identity)
- name: "wr_e_email_exact"
  description: "Email exact match search"
  collection: "identity"
  type: "find"
  filter:
    emails.emailAddress: "${param:email}"

# WR-F: DOB with Identifier
- name: "wr_f_dob_name"
  description: "Search by DOB and name"
  collection: "identity"
  type: "find"
  filter:
    individual.birthDate: "${param:dob}"
    common.fullName: { $regex: "${param:name}", $options: "i" }

# WR-G: Entity Type Filter
- name: "wr_g_entity_type"
  description: "Filter by entity type"
  collection: "identity"
  type: "find"
  filter:
    common.entityTypeIndicator: "${param:entityType}"
    common.fullName: { $regex: "${param:name}", $options: "i" }

# WR-H: Full Name Search
- name: "wr_h_full_name"
  description: "Full name search with first, middle, last"
  collection: "identity"
  type: "find"
  filter:
    individual.firstName: "${param:firstName}"
    individual.middleName: "${param:middleName}"
    individual.lastName: "${param:lastName}"

# WR-Q: TIN Last 4 with Name
- name: "wr_q_tin_last4_name"
  description: "Search by TIN last 4 and name"
  collection: "identity"
  type: "find"
  filter:
    common.taxIdentificationNumberLast4: "${param:tinLast4}"
    common.fullName: { $regex: "${param:name}", $options: "i" }

# WR-S: ID Document Search
- name: "wr_s_id_document"
  description: "Search by DL or Passport number"
  collection: "identity"
  type: "find"
  filter:
    common.identifications.documentNumber: "${param:documentNumber}"
```

---

## 5. Performance Requirements

From the PDF:
- **Response Time**: < 200 milliseconds
- **Scale**: Support up to 4000 one-to-many relationships per customer
- **Concurrent Load**: Auto-scaling with throttling support

### 5.1 Benchmark Targets

| Query Type | Target P95 Latency | Target P99 Latency |
|------------|-------------------|-------------------|
| Single Collection Lookup | < 50ms | < 100ms |
| Multi-Collection Join (2) | < 100ms | < 150ms |
| Multi-Collection Join (3+) | < 150ms | < 200ms |
| Full Text/Fuzzy Search | < 100ms | < 150ms |

---

## 6. Implementation Phases

### Phase 1: Data Model Updates
1. Enhance `identity` generator with embedded `emails` array
2. Add `account` collection generator (with `accountHolders` array for multiple identities)
3. Add `taxIdentificationNumberLast4` computed field to identity
4. Update load command to support new account collection
5. Generate realistic account-to-customer relationships (joint accounts, authorized users)

### Phase 2: Index Implementation
1. Create index configuration in YAML
2. Implement `IndexManager` to create indexes
3. Add index verification and reporting
4. Benchmark index creation time

### Phase 3: Query Engine Enhancement
1. Implement multi-collection join queries
2. Add parameter substitution for `${intersect:...}`
3. Add query step execution with intermediate results
4. Implement result aggregation across collections

### Phase 4: Query Configuration
1. Update `sample-query-config.yaml` with all use cases
2. Add parameter generators for realistic test data
3. Configure expected result counts
4. Add latency thresholds for pass/fail

### Phase 5: Reporting Enhancement
1. Add per-use-case latency reporting
2. Add index usage analysis
3. Add multi-collection query breakdown
4. Generate compliance report vs. 200ms requirement

---

## 7. Data Generation Ratios

| Collection | Ratio | Documents at MEDIUM Scale | Notes |
|------------|-------|---------------------------|-------|
| Identity   | 1     | 100,000 | Each has 1-3 embedded emails |
| Address    | 1     | 100,000 | |
| Phone      | 2.5   | 250,000 | |
| Account    | 1.5   | 150,000 | Each has 1-4 account holders |
| **Total**  |       | **600,000** | |

### Account Holder Distribution
| Relationship Type | Percentage | Description |
|-------------------|------------|-------------|
| Single Holder     | 60%        | One identity per account |
| Joint (2 holders) | 25%        | Married couples, business partners |
| Joint (3+ holders)| 10%        | Family accounts, small business |
| Authorized Users  | 5%         | Credit cards, corporate accounts |

### Embedded Email Distribution
| Emails per Identity | Percentage |
|---------------------|------------|
| 1 email             | 50%        |
| 2 emails            | 35%        |
| 3 emails            | 15%        |

---

## 8. Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Multi-collection joins exceed 200ms | High | Pre-aggregate customer data, optimize indexes |
| Oracle MongoDB API limitations | Medium | Test all query patterns early |
| Partial search (last 4) returns too many results | Medium | Require combined filters, add selectivity |
| Text/fuzzy search performance | Medium | Use Atlas Search or Oracle text indexes |

---

## 9. Next Steps

1. **Review this plan** - Confirm requirements interpretation
2. **Approve data model changes** - New collections and fields
3. **Implement Phase 1** - Data generators
4. **Create indexes and test** - Validate performance
5. **Run full benchmark suite** - Generate compliance report

---

*Document Version: 1.1*
*Created: 2025-12-10*
*Updated: 2025-12-10*
*Based on: Wells Fargo Vector Search Solution Demonstration - Participant Invitation v12052025*

## Revision History
| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-10 | Initial implementation plan |
| 1.1 | 2025-12-10 | Embedded emails in identity (not separate collection), restructured account to support multiple holders per account |
