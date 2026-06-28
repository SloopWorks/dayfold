# Example: a "Starting College" hub

A complete, ready-to-push hub tree exercising the main block types — **milestone,
checklist, contact, document, budget, markdown** — with the **canonical schema
payloads** (ADR 0035), plus **hub visual enrichment** (ADR 0036 — a curated `icon`
+ `accentColor` on `hub.json`, rendered as the hero banner's tile). Use it to see
the full hub renderer end-to-end without hand-authoring, or as a filled reference
that complements the empty `dayfold template <type>` skeletons.

## Push it
1. `dayfold login` (device-grant — approve on your phone)
2. `bash push.sh`
3. Open the app → **Hubs** → **"Sample → Starting College"**

Each file is one resource; `push.sh` sends them top-down (hub → sections → blocks)
with example ids. Every file is kept valid by `ExamplesValidateTest` (CI).

## Adapt
Copy a block file, change its `sectionId` + `payload`, then
`dayfold push <your-id> <file> --block`. Field reference + the markdown the app
renders: `../README.md`.
