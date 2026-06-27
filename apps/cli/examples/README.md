# Dayfold examples — populate a test account

Ready-to-push sample content that exercises the whole MVP — every hub block type,
multiple card types, and the signature **card→hub deep-link** — so you can explore
the app without hand-authoring. All files are CI-validated (`ExamplesValidateTest`,
`ExampleCardsValidateTest`).

## One command

```
dayfold login          # device-grant — approve on your phone
bash push-all.sh       # pushes the hub, then the feed cards
```

Then open the app → **Hubs** ("Sample → Starting College") and **Now** (tap the
Financial Aid card → it opens the hub's Money & Forms section).

## What's here

| Path | What |
|---|---|
| `hub-college/` | A full hub tree — milestone · checklist · contact · document · budget · markdown blocks (canonical schema payloads, ADR 0035). |
| `feed/` | Two Now-feed cards — an invite action card + a contact card that deep-links into the hub. |
| `push-all.sh` | Pushes both (hub first, so the deep-link resolves). |

Each subfolder has its own `README.md` + `push.sh`. To author your own, copy a file,
change the ids/payload, and `dayfold push`. Field reference + the markdown the app
renders: `../templates/README.md`.
