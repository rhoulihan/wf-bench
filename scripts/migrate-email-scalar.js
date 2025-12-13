// Migration script to add scalar primaryEmail field to identity documents
// Run via: mongosh "$CONN" --file scripts/migrate-email-scalar.js

print("Starting email migration...");

const db = db.getSiblingDB('admin');

// Count documents to migrate
const totalCount = db.identity.countDocuments({ emails: { $exists: true, $ne: [] } });
print(`Found ${totalCount} documents with emails array`);

// Use cursor iteration since aggregation pipeline doesn't work on Oracle
let count = 0;
let batch = [];
const batchSize = 100;

db.identity.find({ emails: { $exists: true, $ne: [] } }, { _id: 1, emails: 1 }).forEach(function(doc) {
  if (doc.emails && doc.emails.length > 0 && doc.emails[0].emailAddress) {
    batch.push({
      updateOne: {
        filter: { _id: doc._id },
        update: { $set: { primaryEmail: doc.emails[0].emailAddress } }
      }
    });

    if (batch.length >= batchSize) {
      db.identity.bulkWrite(batch, { ordered: false });
      count += batch.length;
      if (count % 1000 === 0) {
        print(`Processed ${count} documents...`);
      }
      batch = [];
    }
  }
});

// Process remaining batch
if (batch.length > 0) {
  db.identity.bulkWrite(batch, { ordered: false });
  count += batch.length;
}

print(`Modified ${count} documents`);

// Verify a sample
const sample = db.identity.findOne({ primaryEmail: { $exists: true } }, { primaryEmail: 1, "emails.emailAddress": 1 });
print("\nSample document:");
printjson(sample);

print("\nMigration complete!");
