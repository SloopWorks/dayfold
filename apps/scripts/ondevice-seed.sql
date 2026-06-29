-- On-device demo seed (apps/scripts/ondevice-demo.sh). Idempotent.
-- A dev user matching the app's dev sign-in (provider=dev, provider_uid=dev-user)
-- so "Continue with Apple" → /auth/dev-token lands on a family that already has
-- hubs. One family-visible hub + one RESTRICTED hub authored by the dev user (so
-- the dev user sees it with the 🔒 lock, exercising the ADR 0030 treatment).
INSERT INTO users(id,display_name) VALUES ('u_dev','Pat') ON CONFLICT DO NOTHING;
INSERT INTO user_identities(id,user_id,provider,provider_uid) VALUES ('ui_dev','u_dev','dev','dev-user') ON CONFLICT DO NOTHING;
INSERT INTO users(id,display_name) VALUES ('u_sam','Sam') ON CONFLICT DO NOTHING;
INSERT INTO families(id,name,created_by) VALUES ('f_dev','The Jacksons','u_dev') ON CONFLICT DO NOTHING;
INSERT INTO memberships(user_id,family_id,role,status,joined_at) VALUES
  ('u_dev','f_dev','owner','active',now()),
  ('u_sam','f_dev','adult','active',now()) ON CONFLICT DO NOTHING;

INSERT INTO hubs(id,family_id,type,title,status,visibility,created_by,start_at) VALUES
  ('h_party','f_dev','party-event','Maya''s birthday party','active','family','u_dev', now()),
  ('h_med','f_dev','medical','Dad''s knee surgery','active','restricted','u_dev', now()) ON CONFLICT DO NOTHING;
INSERT INTO resource_visibility(family_id,hub_id,user_id) VALUES
  ('f_dev','h_med','u_dev'),('f_dev','h_med','u_sam') ON CONFLICT DO NOTHING;

INSERT INTO sections(id,family_id,hub_id,title,ord) VALUES
  ('sec_ov','f_dev','h_party','Overview',0),
  ('sec_shop','f_dev','h_party','Shopping',1),
  ('sec_med','f_dev','h_med','Recovery',0) ON CONFLICT DO NOTHING;
-- Slice 5b: created_by drives the author-only delete gate. blk_ov is authored by the
-- dev user (u_dev) → Delete is offered on-device; blk_link by u_sam → Delete is absent
-- (author-gate proof); the loop-authored blocks stay null (no member delete).
INSERT INTO blocks(id,family_id,section_id,type,body_md,payload,provenance,ord,created_by) VALUES
  ('blk_ov','f_dev','sec_ov','text','12 guests at 3pm. Backyard if it''s dry. Cake is gluten-free.',NULL,'{"source":"claude"}',0,'u_dev'),
  ('blk_chk','f_dev','sec_shop','checklist',NULL,'{"items":[{"id":"01HZSEEDCAKE00000000000001","text":"Sheet cake (gluten-free)","done":false,"due":"11am"},{"id":"01HZSEEDCANDLES0000000002","text":"Candles","done":true,"doneBy":"dev-user","doneAt":"2026-06-28T12:00:00Z"},{"id":"01HZSEEDJUICE000000000003","text":"Juice boxes","done":false}]}','{"source":"user"}',0,NULL),
  ('blk_link','f_dev','sec_shop','link',NULL,'{"url":"https://open.spotify.com/playlist","label":"Party playlist"}','{"source":"user"}',1,'u_sam'),
  ('blk_med','f_dev','sec_med','text','Discharge Thursday. PT three times a week for six weeks.',NULL,'{"source":"claude"}',0,NULL) ON CONFLICT DO NOTHING;
