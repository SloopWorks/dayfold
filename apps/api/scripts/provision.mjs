// M0 provision: seed one family + mint the household-token credential row +
// generate the static secret. The secret is NEVER stored in the DB — it lives
// in the secret store / OS keychain and is compared constant-time vs
// HOUSEHOLD_SECRET (04-auth §M0). The credential row gives family scope +
// revocation (set revoked_at to kill the token).
//
// Usage: DATABASE_URL=... node scripts/provision.mjs "Family Name"
import { randomBytes } from "node:crypto";
import pg from "pg";

const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
const familyName = process.argv[2] || "My Family";
const familyId = "fam_" + randomBytes(6).toString("hex");
const credId = "hcred_" + randomBytes(6).toString("hex");
const secret = randomBytes(32).toString("base64url"); // 256-bit

await pool.query(`INSERT INTO families(id, name) VALUES ($1, $2)`, [familyId, familyName]);
await pool.query(
  `INSERT INTO credentials(id, kind, family_scope, scopes, label)
   VALUES ($1, 'cli', $2, '{content:read,content:write}', 'household token')`,
  [credId, familyId],
);
await pool.end();

// machine-parseable env block + a human note. Secret printed ONCE.
console.log(`# Provisioned family "${familyName}". Store the secret securely — shown once.`);
console.log(`FAMILY_ID=${familyId}`);
console.log(`HOUSEHOLD_CREDENTIAL_ID=${credId}`);
console.log(`HOUSEHOLD_SECRET=${secret}`);
