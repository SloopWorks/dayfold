// ADR 0064 — the suppression gate. Family-scoped rules and every done row BLOCK the write;
// a personal rule does NOT block, it removes its owner from the card's audience so the
// routine still mints for the rest of the family ("your family's feed is unchanged").
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { generateKeyPair, exportJWK } from "jose";
import pg from "pg";
import { applyAllMigrations } from "./_migrations.ts";
import type { ContentResponseRow } from "../src/content/responses.ts";
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.ENABLE_DEV_AUTH = "1"; process.env.DEV_AUTH_SECRET = "dev"; delete process.env.VERCEL_ENV;
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");
// Dynamic: write-guard pulls db.ts, whose pool is built at module load. A static import
// hoists above the env assignments above and connects to the wrong database.
const { suppressedBy } = await import("../src/content/write-guard.ts");

beforeAll(async () => { await applyAllMigrations(q); });
afterAll(async () => { await pool.end(); });

const rule = (over: Partial<ContentResponseRow>): ContentResponseRow => ({
  id: "r", kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
  audience_scope: "family", user_id: null, created_by: "u1",
  label: "l", sublabel: null, note: null, version: 1, ...over,
}) as ContentResponseRow;

describe("suppressedBy (unit)", () => {
  const subject = { subjectRef: "card:c_1", kind: "weather", source: "mb" };

  it("blocks on a matching family mute", () => {
    expect(suppressedBy([rule({})], subject)).toEqual({ blocked: true, excludeUserIds: [] });
  });

  it("excludes the owner on a matching personal mute, and does not block", () => {
    const r = rule({ audience_scope: "personal", user_id: "u_mom" });
    expect(suppressedBy([r], subject)).toEqual({ blocked: false, excludeUserIds: ["u_mom"] });
  });

  it("collects every matching personal owner", () => {
    const rs = [
      rule({ audience_scope: "personal", user_id: "u_mom" }),
      rule({ audience_scope: "personal", user_id: "u_dad", subject_ref: "source:mb", match_scope: "source" }),
    ];
    expect(suppressedBy(rs, subject).excludeUserIds.sort()).toEqual(["u_dad", "u_mom"]);
  });

  it("blocks on a done row regardless of audience shape", () => {
    expect(suppressedBy([rule({ kind: "done", subject_ref: "card:c_1", match_scope: "subject" })], subject).blocked).toBe(true);
  });

  it("ignores non-matching rules", () => {
    expect(suppressedBy([rule({ subject_ref: "kind:traffic" })], subject))
      .toEqual({ blocked: false, excludeUserIds: [] });
  });

  it("an empty rule list suppresses nothing", () => {
    expect(suppressedBy([], subject)).toEqual({ blocked: false, excludeUserIds: [] });
  });
});

const dev = { "content-type": "application/json", authorization: "Bearer dev" };
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const fam = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${t}` }, body: JSON.stringify({ name: uid }) })).json();
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  return { token: t as string, familyId: fam.familyId as string, userId: me.user_id as string };
}
async function memberOf(uid: string, familyId: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ($1,$2,'adult','active')`, [me.user_id, familyId]);
  return { token: t as string, userId: me.user_id as string };
}
const authH = (tok: string) => ({ "content-type": "application/json", authorization: `Bearer ${tok}` });
const put = (fid: string, path: string, tok: string, body: any) =>
  app.request(`/families/${fid}/${path}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });

describe("suppression end-to-end", () => {
  it("a family mute makes the next authored write 409", async () => {
    const o = await ownerOf("sup-1");
    await put(o.familyId, "responses/r_mute", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "family", label: "Weather cards",
    });
    const res = await put(o.familyId, "cards/c_rain", o.token, { kind: "weather", title: "Rain at soccer" });
    expect(res.status).toBe(409);
    const rows = await q(`SELECT 1 FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_rain"]);
    expect(rows.rowCount).toBe(0);
  });

  it("an unrelated kind still writes", async () => {
    const o = await ownerOf("sup-2");
    await put(o.familyId, "responses/r_mute", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "family", label: "Weather cards",
    });
    expect((await put(o.familyId, "cards/c_ok", o.token, { kind: "info", title: "Fine" })).status).toBe(200);
  });

  it("a personal mute strips only that member from audience[]", async () => {
    const o = await ownerOf("sup-3");
    const m = await memberOf("sup-3-b", o.familyId);
    await put(o.familyId, "responses/r_p", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "personal", label: "Weather cards",
    });
    const res = await put(o.familyId, "cards/c_rain2", o.token, {
      kind: "weather", title: "Rain", visibility: "restricted", audience: [o.userId, m.userId],
    });
    expect(res.status).toBe(200);
    const row = await q(`SELECT audience FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_rain2"]);
    expect(row.rows[0].audience).toEqual([m.userId]);   // the muting owner is gone, the other member stays
  });

  // Stripping everyone leaves nobody to write for. Writing it anyway would create a card
  // no member can see — invisible content that still consumes sync + storage.
  it("rejects when stripping empties the audience", async () => {
    const o = await ownerOf("sup-4");
    await put(o.familyId, "responses/r_p", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "personal", label: "Weather cards",
    });
    const res = await put(o.familyId, "cards/c_rain3", o.token, {
      kind: "weather", title: "Rain", visibility: "restricted", audience: [o.userId],
    });
    expect(res.status).toBe(409);
  });

  it("a removed rule stops suppressing", async () => {
    const o = await ownerOf("sup-5");
    await put(o.familyId, "responses/r_m", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "family", label: "Weather cards",
    });
    expect((await put(o.familyId, "cards/c_r", o.token, { kind: "weather", title: "Rain" })).status).toBe(409);
    await app.request(`/families/${o.familyId}/responses/r_m`, { method: "DELETE", headers: authH(o.token) });
    expect((await put(o.familyId, "cards/c_r", o.token, { kind: "weather", title: "Rain" })).status).toBe(200);
  });

  it("marking done tombstones the subject and blocks the next write to it", async () => {
    const o = await ownerOf("sup-6");
    await put(o.familyId, "cards/c_task", o.token, { kind: "action", title: "Verify emergency contact" });
    await put(o.familyId, "responses/r_done", o.token, {
      kind: "done", subject_ref: "card:c_task", match_scope: "subject",
      audience_scope: "family", label: "Verify emergency contact", note: "Confirmed",
    });
    const row = await q(`SELECT deleted_at FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_task"]);
    expect(row.rows[0].deleted_at).not.toBeNull();          // leaves every member's Now
    const res = await put(o.familyId, "cards/c_task", o.token, { kind: "action", title: "Verify emergency contact" });
    expect(res.status).toBe(409);                            // the next run does not re-mint it
  });

  it("serializes a Done write against concurrent card authoring", async () => {
    const o = await ownerOf("sup-done-card-race");
    await put(o.familyId, "cards/c_race", o.token, { kind: "action", title: "Original" });

    const [authored, completed] = await Promise.all([
      put(o.familyId, "cards/c_race", o.token, { kind: "action", title: "Concurrent update" }),
      put(o.familyId, "responses/r_done_race", o.token, {
        kind: "done", subject_ref: "card:c_race", match_scope: "subject",
        audience_scope: "family", label: "Original",
      }),
    ]);

    expect(completed.status).toBe(200);
    expect([200, 409]).toContain(authored.status);
    const card = await q(`SELECT deleted_at FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_race"]);
    expect(card.rows[0].deleted_at).not.toBeNull();
    const done = await q(
      `SELECT id FROM content_responses WHERE family_id=$1 AND kind='done' AND subject_ref=$2 AND deleted_at IS NULL`,
      [o.familyId, "card:c_race"],
    );
    expect(done.rows).toEqual([{ id: "r_done_race" }]);
  });

  it("a subject mute blocks a block write to that node", async () => {
    const o = await ownerOf("sup-7");
    await put(o.familyId, "hubs/h1", o.token, { type: "party-event", title: "Party" });
    await put(o.familyId, "sections/s1", o.token, { hubId: "h1", title: "Plan", ord: 0 });
    await put(o.familyId, "blocks/b1", o.token, { sectionId: "s1", type: "text", body_md: "existing", ord: 0 });
    await put(o.familyId, "responses/r_b", o.token, {
      kind: "mute", subject_ref: "hub:h1/section:s1/block:b1", match_scope: "subject",
      audience_scope: "family", label: "That block",
    });
    const res = await put(o.familyId, "blocks/b1", o.token, { sectionId: "s1", type: "text", body_md: "nope", ord: 0 });
    expect(res.status).toBe(409);
  });

  it("serializes a Done write against concurrent block authoring", async () => {
    const o = await ownerOf("sup-done-block-race");
    await put(o.familyId, "hubs/h_race", o.token, { type: "party-event", title: "Party" });
    await put(o.familyId, "sections/s_race", o.token, { hubId: "h_race", title: "Plan", ord: 0 });
    await put(o.familyId, "blocks/b_race", o.token, {
      sectionId: "s_race", type: "text", body_md: "Original", ord: 0,
    });
    const subject = "hub:h_race/section:s_race/block:b_race";

    const [authored, completed] = await Promise.all([
      put(o.familyId, "blocks/b_race", o.token, {
        sectionId: "s_race", type: "text", body_md: "Concurrent update", ord: 0,
      }),
      put(o.familyId, "responses/r_done_block_race", o.token, {
        kind: "done", subject_ref: subject, match_scope: "subject",
        audience_scope: "family", label: "Original",
      }),
    ]);

    expect(completed.status).toBe(200);
    expect([200, 409, 410]).toContain(authored.status);
    const block = await q(`SELECT deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [o.familyId, "b_race"]);
    expect(block.rows[0].deleted_at).not.toBeNull();
    const done = await q(
      `SELECT id FROM content_responses WHERE family_id=$1 AND kind='done' AND subject_ref=$2 AND deleted_at IS NULL`,
      [o.familyId, subject],
    );
    expect(done.rows).toEqual([{ id: "r_done_block_race" }]);
  });

  it("serializes Done against moving its block to another section", async () => {
    const o = await ownerOf("sup-done-block-move-race");
    await put(o.familyId, "hubs/h_block_move", o.token, { type: "party-event", title: "Party" });
    await put(o.familyId, "sections/s_block_from", o.token, { hubId: "h_block_move", title: "Before", ord: 0 });
    await put(o.familyId, "sections/s_block_to", o.token, { hubId: "h_block_move", title: "After", ord: 1 });
    await put(o.familyId, "blocks/b_block_move", o.token, {
      sectionId: "s_block_from", type: "text", body_md: "Book the band", ord: 0,
    });
    const oldSubject = "hub:h_block_move/section:s_block_from/block:b_block_move";
    const newSubject = "hub:h_block_move/section:s_block_to/block:b_block_move";

    const [moved, completed] = await Promise.all([
      put(o.familyId, "blocks/b_block_move", o.token, {
        sectionId: "s_block_to", type: "text", body_md: "Book the band", ord: 0,
      }),
      put(o.familyId, "responses/r_done_block_move", o.token, {
        kind: "done", subject_ref: oldSubject, match_scope: "subject",
        audience_scope: "family", label: "Book the band",
      }),
    ]);

    const block = (await q(
      `SELECT section_id,subject_ref,deleted_at FROM blocks WHERE family_id=$1 AND id='b_block_move'`,
      [o.familyId],
    )).rows[0];
    const doneRows = (await q(
      `SELECT subject_ref FROM content_responses
        WHERE family_id=$1 AND kind='done' AND deleted_at IS NULL AND id='r_done_block_move'`,
      [o.familyId],
    )).rows;
    if (completed.status === 200) {
      expect([409, 410]).toContain(moved.status);
      expect(block.deleted_at).not.toBeNull();
      expect(doneRows).toEqual([{ subject_ref: oldSubject }]);
    } else {
      expect(completed.status).toBe(404);
      expect(moved.status).toBe(200);
      expect(block).toMatchObject({ section_id: "s_block_to", subject_ref: newSubject, deleted_at: null });
      expect(doneRows).toEqual([]);
    }
  });

  it("serializes Done against moving its parent section to another Hub", async () => {
    const o = await ownerOf("sup-done-section-move-race");
    await put(o.familyId, "hubs/h_section_from", o.token, { type: "party-event", title: "Before" });
    await put(o.familyId, "hubs/h_section_to", o.token, { type: "party-event", title: "After" });
    await put(o.familyId, "sections/s_section_move", o.token, {
      hubId: "h_section_from", title: "Tasks", ord: 0,
    });
    await put(o.familyId, "blocks/b_section_move", o.token, {
      sectionId: "s_section_move", type: "text", body_md: "Confirm the venue", ord: 0,
    });
    const oldSubject = "hub:h_section_from/section:s_section_move/block:b_section_move";
    const newSubject = "hub:h_section_to/section:s_section_move/block:b_section_move";

    const [moved, completed] = await Promise.all([
      put(o.familyId, "sections/s_section_move", o.token, {
        hubId: "h_section_to", title: "Tasks", ord: 0,
      }),
      put(o.familyId, "responses/r_done_section_move", o.token, {
        kind: "done", subject_ref: oldSubject, match_scope: "subject",
        audience_scope: "family", label: "Confirm the venue",
      }),
    ]);

    expect([200, 409]).toContain(moved.status);
    expect([200, 404]).toContain(completed.status);
    const block = (await q(
      `SELECT subject_ref,deleted_at FROM blocks WHERE family_id=$1 AND id='b_section_move'`,
      [o.familyId],
    )).rows[0];
    // The only valid terminal states are a tombstoned old subject or a live, fully re-keyed one.
    // A live moved block plus an old-subject Done would prove the two operations escaped the lock set.
    if (block.deleted_at == null) {
      expect(block.subject_ref).toBe(newSubject);
      expect(completed.status).toBe(404);
      expect((await q(
        `SELECT 1 FROM content_responses
          WHERE family_id=$1 AND kind='done' AND subject_ref=$2 AND deleted_at IS NULL`,
        [o.familyId, oldSubject],
      )).rowCount).toBe(0);
    } else {
      expect(completed.status).toBe(200);
      expect((await q(
        `SELECT 1 FROM content_responses
          WHERE family_id=$1 AND kind='done' AND subject_ref=$2 AND deleted_at IS NULL`,
        [o.familyId, oldSubject],
      )).rowCount).toBe(1);
    }
  });

  it("requires source-Hub authority before moving a section or block", async () => {
    const o = await ownerOf("sup-move-source-owner");
    const m = await memberOf("sup-move-source-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES
      ('move_private_source',$1,'other','Private source','restricted',$2),
      ('move_member_target',$1,'other','Member target','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role)
      VALUES ($1,'move_member_target',$2,'contributor')`, [o.familyId, m.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES
      ('move_private_section',$1,'move_private_source','Private tasks'),
      ('move_target_section',$1,'move_member_target','Target tasks')`, [o.familyId]);
    await q(`INSERT INTO blocks(id,family_id,section_id,type,body_md,provenance,subject_ref)
      VALUES ('move_private_block',$1,'move_private_section','text','Private task','{}',
        'hub:move_private_source/section:move_private_section/block:move_private_block')`, [o.familyId]);

    const sectionMove = await put(o.familyId, "sections/move_private_section", m.token, {
      hubId: "move_member_target", title: "Stolen tasks", ord: 0,
    });
    const blockMove = await put(o.familyId, "blocks/move_private_block", m.token, {
      sectionId: "move_target_section", type: "text", body_md: "Stolen task", ord: 0,
    });

    expect(sectionMove.status).toBe(404);
    expect(blockMove.status).toBe(404);
    expect((await q(
      `SELECT hub_id FROM sections WHERE family_id=$1 AND id='move_private_section'`, [o.familyId],
    )).rows[0].hub_id).toBe("move_private_source");
    expect((await q(
      `SELECT section_id FROM blocks WHERE family_id=$1 AND id='move_private_block'`, [o.familyId],
    )).rows[0].section_id).toBe("move_private_section");
  });

  // This probe deliberately parks two route transactions at the same advisory lock. The production
  // Vercel pool is max=1, so only the ordinary full-suite pool can place both waiters concurrently;
  // the separate VERCEL=1 suite covers the pinned single-connection path with the remaining races.
  it.skipIf(Boolean(process.env.VERCEL))(
    "serializes concurrent same-id creates before either can become a cross-Hub move",
    async () => {
    const o = await ownerOf("sup-same-id-create");
    await put(o.familyId, "hubs/same_id_hub_a", o.token, { type: "other", title: "A" });
    await put(o.familyId, "hubs/same_id_hub_b", o.token, { type: "other", title: "B" });
    await put(o.familyId, "sections/same_id_parent_a", o.token, { hubId: "same_id_hub_a", title: "A" });
    await put(o.familyId, "sections/same_id_parent_b", o.token, { hubId: "same_id_hub_b", title: "B" });

    const runBehindStableLock = async (
      stableRef: string,
      first: () => Promise<Response>,
      second: () => Promise<Response>,
    ) => {
      const blocker = new pg.Client({ connectionString: process.env.DATABASE_URL });
      await blocker.connect();
      try {
        await blocker.query("BEGIN");
        await blocker.query(`SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, [
          JSON.stringify([o.familyId, stableRef]),
        ]);
        const pending = [first(), second()];
        let waiting = 0;
        for (let attempt = 0; attempt < 100 && waiting < 2; attempt += 1) {
          const observed = await blocker.query(
            `SELECT count(*)::int AS waiting FROM pg_stat_activity
              WHERE datname=current_database() AND wait_event_type='Lock' AND wait_event='advisory'`,
          );
          waiting = Number(observed.rows[0].waiting);
          if (waiting < 2) await new Promise((resolve) => setTimeout(resolve, 10));
        }
        expect(waiting).toBeGreaterThanOrEqual(2);
        await blocker.query("COMMIT");
        return Promise.all(pending);
      } finally {
        await blocker.query("ROLLBACK").catch(() => {});
        await blocker.end();
      }
    };

    const sectionResults = await runBehindStableLock(
      "topology:section:same_id_section",
      () => put(o.familyId, "sections/same_id_section", o.token, { hubId: "same_id_hub_a", title: "A" }),
      () => put(o.familyId, "sections/same_id_section", o.token, { hubId: "same_id_hub_b", title: "B" }),
    );
    expect(sectionResults.map((r) => r.status).sort()).toEqual([200, 409]);

    const blockResults = await runBehindStableLock(
      "topology:block:same_id_block",
      () => put(o.familyId, "blocks/same_id_block", o.token, {
        sectionId: "same_id_parent_a", type: "text", body_md: "A", ord: 0,
      }),
      () => put(o.familyId, "blocks/same_id_block", o.token, {
        sectionId: "same_id_parent_b", type: "text", body_md: "B", ord: 0,
      }),
    );
      expect(blockResults.map((r) => r.status).sort()).toEqual([200, 409]);
    },
  );

  it("rejects a block write when its section moves after authorization", async () => {
    const o = await ownerOf("sup-section-move-owner");
    const m = await memberOf("sup-section-move-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES
      ('move_from_hub',$1,'other','From','restricted',$2),
      ('move_to_hub',$1,'other','To','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role)
      VALUES ($1,'move_from_hub',$2,'contributor')`, [o.familyId, m.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title)
      VALUES ('moving_section',$1,'move_from_hub','Tasks')`, [o.familyId]);

    const subject = "hub:move_from_hub/section:moving_section/block:moving_block";
    const lockKey = JSON.stringify([o.familyId, subject]);
    // Keep the test's artificial lock outside the app pool. In Vercel mode that pool is
    // intentionally max=1; consuming it here would test the harness, not the request path.
    const blocker = new pg.Client({ connectionString: process.env.DATABASE_URL });
    await blocker.connect();
    let pending: ReturnType<typeof put>;
    try {
      await blocker.query("BEGIN");
      await blocker.query(`SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, [lockKey]);
      pending = put(o.familyId, "blocks/moving_block", m.token, {
        sectionId: "moving_section", type: "text", body_md: "Must stay in From", ord: 0,
      });

      let waiting = false;
      for (let attempt = 0; attempt < 100 && !waiting; attempt += 1) {
        const observed = await blocker.query(
          `SELECT EXISTS (
             SELECT 1 FROM pg_stat_activity
              WHERE datname=current_database() AND wait_event_type='Lock' AND wait_event='advisory'
           ) AS waiting`,
        );
        waiting = observed.rows[0].waiting === true;
        if (!waiting) await new Promise((resolve) => setTimeout(resolve, 10));
      }
      expect(waiting).toBe(true);

      // Simulate an authorized owner moving the section while the stale member request waits.
      await blocker.query(`UPDATE sections SET hub_id='move_to_hub', version=version+1, updated_at=now()
        WHERE family_id=$1 AND id='moving_section'`, [o.familyId]);
      await blocker.query("COMMIT");
    } finally {
      await blocker.query("ROLLBACK").catch(() => undefined);
      await blocker.end();
    }

    const result = await pending!;
    expect(result.status).toBe(409);
    expect((await q(`SELECT 1 FROM blocks WHERE family_id=$1 AND id='moving_block'`, [o.familyId])).rowCount).toBe(0);
  });
});
