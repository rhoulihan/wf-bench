// Migration script to add scalar primaryEmail field to identity documents
// Run via: mongosh "$CONN" --file scripts/migrate-email-scalar.js

print("Starting email migration...");

const db = db.getSiblingDB('admin');

// Count documents to migrate
const totalCount = db.identity.countDocuments({ emails: { $exists: true, $ne: [] } });
print(`Found ${totalCount} documents with emails array`);

// Add primaryEmail scalar field extracted from emails[0].emailAddress
const result = db.identity.updateMany(
  { emails: { $exists: true, $ne: [] } },
  [
    {
      $set: {
        primaryEmail: { $arrayElemAt: ["$emails.emailAddress", 0] }
      }
    }
  ]
);

print(`Modified ${result.modifiedCount} documents`);
print(`Matched ${result.matchedCount} documents`);

// Verify a sample
const sample = db.identity.findOne({ primaryEmail: { $exists: true } }, { primaryEmail: 1, "emails.emailAddress": 1 });
print("\nSample document:");
printjson(sample);

print("\nMigration complete!");
