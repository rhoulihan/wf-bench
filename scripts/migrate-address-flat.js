// Migration script to convert address documents from array to flat structure
// Run via: mongosh "$CONN" --file scripts/migrate-address-flat.js
//
// Old structure: One document per customer with addresses array
//   { _id: { customerNumber, customerCompanyNumber }, addresses: [{ cityName, stateCode, ... }] }
//
// New structure: One document per address with flat fields
//   { _id: { customerNumber, customerCompanyNumber, addressKey }, cityName, stateCode, ... }

print("Starting address migration to flat structure...");

const db = db.getSiblingDB('admin');

// First check if we have old-style documents (with addresses array)
const oldStyleCount = db.address.countDocuments({ addresses: { $exists: true } });
print(`Found ${oldStyleCount} old-style documents with addresses array`);

if (oldStyleCount === 0) {
  print("No migration needed - addresses already in flat format or collection is empty");
  // Verify new format
  const newStyleCount = db.address.countDocuments({ cityName: { $exists: true } });
  print(`Found ${newStyleCount} documents with flat cityName field`);
  quit();
}

// Process old documents and create new flat documents
let processed = 0;
let created = 0;
let batch = [];
const batchSize = 100;

db.address.find({ addresses: { $exists: true } }).forEach(function(doc) {
  const customerNumber = doc._id.customerNumber;
  const customerCompanyNumber = doc._id.customerCompanyNumber || 1;

  if (doc.addresses && Array.isArray(doc.addresses)) {
    doc.addresses.forEach(function(addr, idx) {
      // Generate unique addressKey if not present
      const addressKey = addr.systemOfRecord?.addressKey ||
        `00100${addr.countryCode || 'US'}${(addr.postalCode || '').replace(/[- ]/g, '')}${addr.stateCode || 'XX'}${String(Math.random()).slice(2,11)}${String(idx).padStart(5,'0')}${String(customerNumber % 10000000000).padStart(10,'0')}`;

      const newDoc = {
        _id: {
          customerNumber: customerNumber,
          customerCompanyNumber: customerCompanyNumber,
          addressKey: addressKey
        },
        addressUseCode: addr.addressUseCode || 'CUSTOMER_RESIDENCE',
        addressLines: addr.addressLines || [],
        addressLine1: addr.addressLine1 || (addr.addressLines && addr.addressLines[0]) || '',
        cityName: addr.cityName,
        stateCode: addr.stateCode,
        postalCode: addr.postalCode,
        countryCode: addr.countryCode || 'US',
        lastMaintenanceDate: addr.lastMaintenanceDate,
        systemOfRecord: addr.systemOfRecord || {
          addressKey: addressKey,
          addressUseOccurrenceNumber: idx + 1,
          addressTemporaryIndicator: "1",
          effectiveDate: "2301011",
          expirationDate: "9999999"
        },
        metaData: addr.metaData || doc.metaData
      };

      batch.push({ insertOne: { document: newDoc } });
      created++;

      if (batch.length >= batchSize) {
        try {
          db.address.bulkWrite(batch, { ordered: false });
        } catch (e) {
          // Ignore duplicate key errors for idempotency
          if (!e.message.includes('duplicate key')) {
            print(`Error: ${e.message}`);
          }
        }
        batch = [];
        if (created % 1000 === 0) {
          print(`Created ${created} flat documents...`);
        }
      }
    });
  }
  processed++;
});

// Process remaining batch
if (batch.length > 0) {
  try {
    db.address.bulkWrite(batch, { ordered: false });
  } catch (e) {
    if (!e.message.includes('duplicate key')) {
      print(`Error: ${e.message}`);
    }
  }
}

print(`Processed ${processed} old-style documents`);
print(`Created ${created} new flat documents`);

// Now delete old-style documents
print("\nDeleting old-style documents...");
const deleteResult = db.address.deleteMany({ addresses: { $exists: true } });
print(`Deleted ${deleteResult.deletedCount} old-style documents`);

// Verify results
const finalCount = db.address.countDocuments({});
const flatCount = db.address.countDocuments({ cityName: { $exists: true } });
print(`\nFinal counts:`);
print(`  Total documents: ${finalCount}`);
print(`  Flat documents: ${flatCount}`);

// Sample document
const sample = db.address.findOne({ cityName: { $exists: true } });
print("\nSample flat document:");
printjson(sample);

print("\nMigration complete!");
