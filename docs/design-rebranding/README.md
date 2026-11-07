# Handoff: firn — "Instrument" redesign + GGUF Model World

> **Rebrand:** the app is now called **firn** (formerly AIpaca, working title miniAgent). Use "firn" in all UI copy, the launcher label and the store listing. Start with the rebrand checklist in §0.

## Overview
A full visual redesign of the Android app, now **firn** (Chat, Memory, Models, Server, History sheet) in a minimal-futurist register — true black, white hairlines, tracked uppercase chrome — anchored by one signature element: the **Model World**, a live 3D point-cloud on the chat empty state.

- **No model loaded:** the world is a rotating Fibonacci sphere.
- **Model loaded:** the app reads the `.gguf` header and the sphere morphs into a shape built from that model's architecture (one ring per block, one point per KV head, radius from `embedding_length`, helical twist from `context_length`, dot weight from quantisation).

Target repo: `janik96v/AIpaca` (Kotlin, Jetpack Compose, llama.cpp via JNI).

## About the design files
`firn-prototype.dc.html` is a **design reference built in HTML** — the working prototype of the intended look and behaviour, not production code. It runs inside the Claude Design canvas (page "New App Final"); here it is a readable source reference: the markup shows every screen, the `<script data-dc-script>` block at the bottom holds all logic and copy. Recreate it in the existing Compose codebase using its patterns (`ui/theme/*`, `ui/components/*`, `EngineState`). Do not ship the HTML.

When this README and the prototype disagree, **this README wins**.

## Fidelity
**High-fidelity.** Colours, type sizes, tracking, spacing and all Model World maths are final. Recreate pixel-for-pixel (1 CSS px = 1 dp).

---

## 0. Rebrand checklist: AIpaca / miniAgent → firn (do this first, as its own commit)
The app is not published yet, so the application ID changes too. After this step the old dev build is a separate app on test devices: uninstall it by hand.

1. **Application ID & namespace:** `com.aipaca.app` → `ch.firn.app` in `app/build.gradle(.kts)` (`applicationId` and `namespace`). The ID must be unique on Google Play and cannot change after the first upload; `ch.firn.app` is the working choice, swap it for a reverse domain you own if you register one (e.g. getfirn.app → `app.getfirn`).
2. **Kotlin packages:** move `app/src/main/kotlin/com/aipaca/app/**` → `ch/firn/app/**`; update every `package` / `import`, the manifest, ProGuard/R8 rules, test sources and any `BuildConfig` references.
3. **JNI — easy to miss:** native symbols in `cpp/llama_jni.cpp` (and the whisper bridge) are named after the Java package (`Java_com_aipaca_app_…`). Rename them to `Java_ch_firn_app_…` or switch to `RegisterNatives`. Otherwise the app builds but crashes with `UnsatisfiedLinkError` on first model load.
4. **App name:** `app_name` = `firn` (lowercase) in `res/values*/strings.xml`; check notification channel names, the server's user-agent / mDNS name, share sheets and the About text.
5. **Launcher icon:** adaptive icon (`mipmap-anydpi-v26`) from `brand/firn-app-icon.svg` — background `#000` (optional haze), foreground = the mark inside the 66dp safe zone; monochrome themed icon from `brand/firn-mark-small-white.svg`; legacy PNGs from `logo/png/`. Delete the alpaca icons.
6. **Splash screen** (SplashScreen API): background `#000`, icon `firn-mark-small-white`, no text.
7. **Code names:** `AlpacaColors` → `FirnColors`, `AlpacaBottomNav` (replaced by the rail anyway), theme `Theme.AIpaca*` → `Theme.Firn*`, `rootProject.name = "firn"` in `settings.gradle(.kts)`. Rename internal file / DB / preference names only while there is no user data to migrate — which is now.
8. **Done when** `grep -riE "aipaca|alpaca|miniagent" app/ cpp/ --include=*.{kt,kts,xml,cpp,h,txt}` returns nothing outside changelogs.
9. *(Optional, by hand)* rename the GitHub repo `janik96v/AIpaca` → `firn`; GitHub keeps a redirect, update the local remote URL.

## 1. The Model World (priority — build this right after the rebrand)

### 1.1 Data source — already in the repo
`LlamaCppEngine.probeGgufMeta(path)` → `GgufProbeResult(arch, isRecurrentKV, nCtxTrain, nParams, nEmbd, nLayer, nHeadKv, dHead, quant)` reads the header only, no weights. It is exactly what the world needs.

Mapping:

| World input | Source | Notes |
| --- | --- | --- |
| `blocks` | `nLayer` (`{arch}.block_count`) | ring count |
| `kv` | `nHeadKv` (`{arch}.attention.head_count_kv`) | points per ring |
| `heads` | `{arch}.attention.head_count` | **readout only.** Native side already reads `n_head` (llama_jni.cpp ~L298); add `nHead` to the JSON + `GgufProbeResult` |
| `embd` | `nEmbd` | ring radius |
| `ctx` | `nCtxTrain` | helical twist |
| `bits` | derived from `quant` | dot weight — see table below |
| `split` | `true` for encoder–decoder archs | two towers (Whisper, T5). Whisper runs through `WhisperEngine` (ggml `.bin`): hard-code its params (`blocks 12, kv 8, embd 512, ctx 448, bits 16, split true`) or read the ggml header |
| `params` | `nParams` | readout only, format `4.0 B` / `137 M` |

Bits per weight: `Q4_0 → 4`, `Q4_K_M / Q4_K_S → 4.5`, `Q5_* → 5.5`, `Q6_K → 6.5`, `Q8_0 → 8`, `F16 / BF16 → 16`, unknown → `4.5`.

Probe **before** load so the morph can start while weights are still loading (see 1.4).

### 1.2 Geometry — see `reference/ModelWorldGeometry.kt`
All coordinates are in unit space (sphere radius 1). `N = 420` points always — the sphere and the model shape share the same point budget so every point has a morph partner.

**Sphere (idle):** Fibonacci lattice
```
golden = π(3 − √5)
for i in 0 until N:  y = 1 − 2i/(N−1);  r = √(1 − y²);  θ = golden·i
                     p = (cos θ·r, y, sin θ·r)
```
Sphere links: for each i, scan j > i and link the first **2** neighbours with distance < `1.25·√(4π/N)`.

**Model shape:**
```
per    = clamp(kv, 6, 12)                         // points per ring
rad0   = 0.28 + 0.26·min(1, embd/4096)            // ring radius
ctxF   = clamp(log2(max(2,ctx)/1024) / 7, 0, 1)
twist  = (2π/per) · (0.10 + 0.34·ctxF)            // per-ring rotation — a fraction of the ring's own spacing, never aliases
stacks = split ? [(ceil(blocks/2), x=−0.44), (floor(blocks/2), x=+0.44)]
               : [(min(blocks, floor(N/per)), x=0)]
for each stack (rings, ox):
  rad   = split ? rad0·0.62 : rad0
  hspan = 0.42 + 0.50·min(1, rings/36)            // half-height of the column
  for l in 0 until rings:
    y = rings==1 ? 0 : −hspan + 2·hspan·l/(rings−1)
    for k in 0 until per:
      θ = l·twist + 2πk/per
      point = (cos θ·rad + ox, y, sin θ·rad)
      link ring:     (first+k, first+(k+1)%per)
      link residual: (prev+k, first+k)            // if l > 0
Truncate points to N; drop links referencing indices ≥ N.
dotW = 0.62 + 0.52·min(1, bits/8)
```
Cache the shape per model id.

### 1.3 Rendering (every frame)
Canvas: **224 × 224 dp**, centred, bottom-aligned in the empty-state area. Draw at device density (prototype uses a 520 px backing store). Background transparent over `#000`.

```
W = canvas width px;  cx, cy = centre
R  = W·0.40·conv                          // conv animates, see 1.4
a  = accumulated yaw;  t = 0.42 rad fixed pitch
for each point i:
  m-blend:  p = lerp(sphere[i], shape[i], e)            // e = smoothstep(m)
  orphans (i ≥ shape.size): p = sphere[i]·(1 − 0.94e), alpha ×(1 − e)
  x  = px·cos a + pz·sin a
  z0 = −px·sin a + pz·cos a
  screen = (cx + x·R,  cy + (py·cos t − z0·sin t)·R)
  depth  = py·sin t + z0·cos t            // −1…1
  d = (depth + 1)/2
Links (1 px stroke, white):  alpha = (0.05 + 0.23·avg(d_A, d_B)) × fade
  sphere links fade = 1 − e;  shape links fade = e;  skip if fade < 0.03
Dots (white fill):  alpha = (0.10 + 0.82·d) × orphanAlpha
                    radius = (0.8 + 2.1·d) × lerp(1, dotW, e)   // in backing-store px at 520
Frame ring: circle radius W·0.455, 1 px, white α 0.09
```
Draw order: sphere links → shape links → dots → frame ring. In Compose: a `Canvas` driven by `withFrameNanos` inside `LaunchedEffect`; keep point arrays as `FloatArray`, not objects.

### 1.4 Motion states
Frame-rate independent exponential easing, `dt` clamped to 0.05 s.

| State | yaw speed (rad/s) | `conv` target | `m` target |
| --- | --- | --- | --- |
| Idle, no model | 0.11 | 1 | 0 |
| Loading (probe + load) | 1.6 | 0.34 | 0 |
| Ready | 0.11 | 1 | 1 |
| Streaming / thinking | 0.8 | 1 | 1 |

```
conv += (target − conv) · min(1, dt·3.2)
m    += (target − m)    · min(1, dt·(target==1 ? 2.2 : 3.6))
e     = m²(3 − 2m)
```
So: load → sphere contracts and spins fast → on ready it expands and unfolds into the model. Unload → folds back into the sphere (faster, 3.6).

Prototype fakes load as 1.5 s. Real: `loading` = `EngineState` load in flight.

### 1.5 Header readout (under the world)
3-column grid, `gap 11dp row / 12dp col`, top hairline `1dp rgba(255,255,255,.12)`, `padding-top 12dp`. Six cells, each label over value (gap 5dp):

`BLOCKS 36` · `HEADS 32 / 8 KV` · `EMBD 2560` · `CTX 128K` · `QUANT Q4_K_M` · `PARAMS 4.0 B`

- Label: Inter 400, 9sp, tracking .2em, `rgba(255,255,255,.72)`
- Value: Inter 400, 11sp, tracking .08em, `#FAFAFA`, single line ellipsis
- No model: single line `NO HEADER READ · IDLE`, 9sp, tracking .24em, `.72` white, same hairline.

---

## 2. Screens
All buttons, chips, tabs and switches use the **Soft square** controls in §4 — "primary", "secondary", "small", "chip", "segmented" and "switch" below refer to those specs.

Device reference: 412 × 892 dp. Root background `#000` + top haze: `radial-gradient(120% 52% at 50% −6%, rgba(150,175,205,.13) 0%, rgba(90,110,135,.05) 38%, transparent 72%)`, non-interactive.

### Shell
- **Left rail** 66dp wide (collapsible). Top: the firn mark, small version (24×18dp, `brand/firn-mark-small-white.svg`) + thin caret-left 12sp at .5 white → collapses rail. Items Chat / Mem / Mdl / Srv: icon 20sp thin + label 10sp tracking .18em, padding 14dp vertical, gap 8dp. Active: `#FFF` + 1×16dp white bar at left edge; inactive `rgba(255,255,255,.75)`. Hover/press bg `.04`. Bottom: history icon 18sp at .72.
- Content column border-left `1dp rgba(255,255,255,.08)`.
- Collapsed rail: header shows list icon + firn mark (20×15dp, opens rail) and history icon on the right.
- **Status line** (top, padding 20dp 20dp 0): firn small mark 18×13dp (while streaming: `brand/firn-mark-thinking-small-animated.svg`, a lit path wandering through the net, 0.7 s per step) + 10sp tracking .24em uppercase `.8` white, ellipsis. Copy:
  - `NO MODEL LOADED`
  - `READING {arch}.GGUF HEADER` (loading)
  - `THINKING` (streaming)
  - `{MODEL NAME} · GPU · READY`
- **Position ladder**: 4 × 3dp squares, right 8dp, vertically centred, gap 10dp; active opacity 1, others .26.

### Chat — empty
Padding 6/20/22. World + readout fill top (bottom-aligned, gap 13dp), then copy block (gap 15dp):
- **No model:** title `LOAD A MODEL` (Inter 300, 15sp, lh 1.9, tracking .36em, `#FAFAFA`); hairline gradient `.2 → transparent 70%`; body "firn reads the .gguf header and builds the model from it — one ring per block, one point per KV head." (13sp/1.7, `.76`, max 34ch); primary button "Open models" (cube icon).
- **Model ready:** `ASK ME ANYTHING`; body "Runs on this phone. Nothing leaves it unless you say so." (max 32ch); two suggestion chips "Summarise a PDF", "What do you recall" (chip, padding 10/14, 13sp).

### Chat — conversation
List padding 20/20/8, gap 22dp. Each message: meta (10sp tracking .28em) over body (13sp/1.75, max 268dp, pre-wrap).
- User: right-aligned, meta `YOU` `.78`, 1dp `.2` border box, padding 12/14, `#FAFAFA`. (This content frame stays square and faint so it never reads as a button.)
- Agent: left-aligned, meta row = firn small mark (20dp wide) + `FIRN` `#FFF`, gap 8dp. While that message streams, the mark is the animated thinking mark. No box, 1dp `.55` left rule, padding-left 14, text `.92`.

### Composer
Padding 8/16/14. Above the input: a 20dp-high sine line (1dp stroke, white .4), scrolling left continuously (5.5 s loop). Idle: amplitude ×0.12, opacity .45. Streaming: amplitude ×1, opacity 1.
Row (gap 12dp, bottom-aligned): paperclip 19sp `.45` · input (13sp/1.6, transparent, no border; placeholder `MESSAGE` or `LOAD A MODEL FIRST` at `.62`, tracking .22em) · send button (§4). Send disabled when empty, streaming, or **no model loaded**.

### Memory
Padding 28/20/24, gap 18dp. Title `MEMORY` (300, 16sp, tracking .42em) + hairline (`.2 → transparent 76%`). Then:
- **Learning** settings card (§4) with switch: title "Learning", meta "Consolidates while charging · 4 new notes" / "Paused — nothing is written". The whole card toggles.
- **Tabs** Soul / You / Facts / Sessions as a segmented control (§4).
- Tab hint 12sp/1.7 `.74`.
- Entries: date 10sp .28em `.78`, text 13sp/1.75 `.9`, hairline `.13 → transparent 82%`. Entries are content, not tappable.
- Secondary button "Edit this file" (pencil-simple icon).

### Models — installed
Title `MODELS` + hairline. Meta row: `ON THIS PHONE · 3` / `5.3 / 128 GB` (10sp .26em `.78`). Rows padding 16/0, bottom border `.08`, gap 12:
- 2×2 dot mark (2dp squares), opacity 1 if loaded else .22
- Name 13sp/1.3 + meta 10sp/1.6 .24em `.78`. Meta when loaded: `36 BLOCKS · 32/8 HEADS`; otherwise `Q4_K_M · 2.7 GB · 128K CTX`.
- Small button: "Load" (secondary) / "Reading" while loading / "Unload" when loaded (primary). Tapping the loaded row unloads.
- Trash icon 16sp `.55`. Deleting the loaded model unloads it.
Below: primary "Browse models" (full width, magnifying-glass icon left, arrow-right right), secondary "Open a .gguf file" (folder-open icon, fit-content).

### Models — browse
Back link `← MODELS` (10sp .24em `.78`). Title `AVAILABLE / MODELS` (300, 16sp/1.8, .4em, two lines). Search underline field (`.2` bottom border, placeholder `SEARCH HUGGING FACE`). Filter chips All/Chat/Code/Vision/Speech/Embedding (chip; selected filled). Rows: name, meta, note (12sp/1.7 `.74`) + small action: "Download" (secondary, download icon) · "Cancel" during download (x icon) · "Installed" (check icon) and "Too large" (prohibit icon) as text `.62` without surface; too-large rows at opacity .55, note appends "Needs 12 GB RAM.". Download progress: 1dp track `.16` with white fill + `NN%` label.

### Server
Title `SERVER` + hairline. Blurb 13sp/1.7 `.78` (max 34ch). URL `https://192.168.1.42:8443` or `OFFLINE` (13sp .16em). Full-width power button (power icon, padding 15): "Start server" primary / "Stop server" secondary. `PAIRED DEVICES · 1` list: name + `SHA256:8F3A…C21D`, close icon. Secondary button "Pair a new device" (plus icon).

### History sheet
Scrim `rgba(0,0,0,.76)` + 2dp blur. Bottom sheet max 78% height, bg `#050505`, top border `1dp .2`, shadow `0 −24 60 rgba(0,0,0,.6)`, padding 20/20/22, gap 16. Title `HISTORY` (300, 12sp .34em) + close. Primary button "New chat" (plus icon). Rows: title 13sp/1.4 + meta `12 LINES · SEP 13 09:41` 10sp .24em `.78`.

---

## 3. State
- `loadedModel: GgufProbeResult?` (+ id/name) — drives world shape, readout, status line, composer gating.
- `isLoading`, `isStreaming` — drive world motion, composer sine and the thinking mark.
- World-internal (not recomposition state): `yaw`, `conv`, `m`, cached `shape`, sphere arrays. Keep in `remember { }` holders mutated inside the frame loop; only the canvas invalidates.
- UI: `screen`, `railHidden`, `historyOpen`, `memTab`, `browse`, `filter`, `query`, downloads.

## 4. Design tokens
**Controls — "Soft square" (final).**
- **Rules:** one primary (white) action per screen; everything else tappable sits on a dark surface; plain text on black is never tappable; selected = filled `#FAFAFA` with `#000` text. Button labels are sentence case, never tracked.
- **Primary button:** bg `#FAFAFA`, text `#000`, radius 12dp, padding 13/18, Inter 500 14sp, Phosphor Thin icon 19sp, gap 9. Pressed `#E6E6E6`. Used for: Open models, Browse models (full width, arrow right), Start server, New chat, Unload.
- **Secondary button:** bg `#1A1A1A`, 1dp border `rgba(255,255,255,.08)`, text `#FAFAFA`, same size. Pressed `#242424`. Used for: Edit this file, Open a .gguf file, Pair a new device, Stop server.
- **Small button:** padding 9/14, radius 12, Inter 500 12sp. Load (secondary) / Unload (primary) in model rows; catalog Download (secondary, icon 15sp); Installed / Too large = no surface, text `.62`.
- **Chips & filters:** padding 8/13 (suggestions 10/14, 13sp), radius 12, Inter 500 12sp; unselected `#1A1A1A` + `.08` border, text `.88`; selected `#FAFAFA` / `#000`.
- **Segmented control (Memory tabs):** track `#141414`, radius 12, padding 3, gap 2; cells radius 9, padding 9 vertical, Inter 500 13sp; active `#FAFAFA` / `#000`, inactive text `.82`.
- **Switch:** 46×28dp, radius 8, padding 3; knob 22dp, radius 5, shadow `0 1 2 rgba(0,0,0,.4)`. On: track `#FAFAFA`, knob `#000`, right. Off: track `#2A2A2A`, knob `#8C8C8C`, left.
- **Settings card (Memory · Learning):** bg `#111`, 1dp `rgba(255,255,255,.06)`, radius 14, padding 14/16; title Inter 500 14sp, meta 12sp `.66–.74`; whole card toggles, switch on the right. Pressed `#161616`.
- **Send:** 40×40dp, radius 12; enabled `#FAFAFA` with `#000` arrow (18dp vector, 2.2 stroke, round caps — heavier than the thin icon set so it reads on the white fill), disabled `#1A1A1A` with `.45` arrow.

**Colour** — only white on black; hierarchy through alpha.
- Background `#000000`; sheet `#050505`; primary text `#FAFAFA`; accent/active `#FFFFFF`; control surfaces `#111` / `#141414` / `#1A1A1A` / `#242424` (pressed); switch off track `#2A2A2A`.
- White alpha steps: `.92` agent body · `.90` memory text · `.80` status · `.78` meta labels · `.76/.74` body/secondary · `.72` micro labels · `.62` placeholder · `.55` disabled icon · `.45` tertiary icon · `.20` content frames · `.12/.13` hairlines · `.08` row dividers / column rule / control borders · `.04–.07` pressed fills
- All text passes WCAG AA (placeholder ≈ 6:1).

**Type** — Inter only (bundle via `res/font`, Option A in `Typography.kt`; the repo currently falls back to Roboto). Tracked uppercase for chrome only; body and buttons stay sentence case.
- Screen title 300 · 16sp · lh 1.6 · .42em
- Empty-state title 300 · 15sp · lh 1.9 · .36em
- Body 400 · 13sp · lh 1.7–1.75
- Secondary 400 · 12sp · lh 1.6–1.7
- Chrome/labels 400 · 10sp · .18–.30em uppercase
- Button labels 500 · 14sp (small 12sp, tabs 13sp) · sentence case, no tracking
- Micro 400 · 9sp · .20–.24em
- Letter-spacing in `em` → Compose `sp` = em × fontSize (e.g. .24em @ 10sp = 2.4sp).

**Shape** — zero corner radius for layout, hairlines, content frames and UI marks; controls use the soft-square radii above (12dp buttons, 14dp cards, 8dp switch). Borders 1dp. Squares for all UI marks (ladder 3dp, list marks 2dp). The brand mark is the firn logo, never redrawn: use the files in `brand/`.

**Spacing** — screen padding 20dp horizontal; section gap 18dp; row padding 15–16dp vertical; hit targets ≥ 40dp.

## 5. Assets
- Icons: Phosphor **Thin** weight — chat-circle, brain, cube, hard-drives, clock-counter-clockwise, caret-left, list, paperclip, trash, magnifying-glass, folder-open, pencil-simple, arrow-left/right, download-simple, check, x, prohibit, power, plus. Use the Phosphor Android/Compose package or export the thin SVGs as vector drawables. The send arrow is a custom vector (§4).
- No bitmaps in the UI. The world is procedural.
- **Brand (`brand/`):** `firn-mark-small-white.svg` (rail, header, status line, chat meta), `firn-mark-thinking-small-animated.svg` (thinking state at small sizes), `firn-mark.svg` and `firn-mark-thinking-animated.svg` (large, with glowing ridge), `firn-app-icon.svg` (launcher master, 1024) and `firn-app-icon-small.svg` (≤ 192 px), lockups and wordmarks. Convert to vector drawables; for the animated marks, drive the lit path from `isStreaming` in Compose rather than shipping SMIL. **Thinking path:** six steps, 0.7 s each, looping: top→left→bottom-left, top→left→bottom-centre, top→right→bottom-centre, top→right→bottom-right, top→left→bottom-right, top→right→bottom-left; lit links/neurons at full opacity, the rest dimmed.
- **Logo package (`logo/`):** all SVGs, PNG exports (app icon 1024/512/192/180/48, favicon 32, marks and lockups) and the logo usage rules in `logo/README.md`.

## 6. Files in this package
- `README.md` — this spec. It wins over the prototype.
- `firn-prototype.dc.html` — the prototype source (all screens + Model World). Logic lives in the `<script data-dc-script>` block at the bottom: `componentDidMount` (sphere), `shapeFor` (model shape), `_tick` (render loop), `renderVals` (all copy and state → style mapping). In the design canvas this file is "New App Final" / `miniAgent Futuristic.dc.html`.
- `reference/ModelWorldGeometry.kt` — a direct Kotlin port of the geometry + per-frame projection, as a starting point for the Compose `Canvas`.
- `brand/` — the firn logo files used by the UI.
- `logo/` — the complete logo package.

Screenshots are not included: the earlier set predates the rebrand and the Soft-square controls. Use the live canvas page for visual comparison.

## Suggested implementation order
0. Rebrand checklist (§0), own commit, app must build and load a model afterwards.
1. `ModelWorldGeometry.kt` + a `ModelWorld` composable rendering the idle sphere.
2. Wire `probeGgufMeta` → shape; add `nHead`; morph on load/unload.
3. Header readout + status line (firn mark, thinking state) + composer gating.
4. Soft-square controls as shared components (§4), then re-skin the remaining screens.
