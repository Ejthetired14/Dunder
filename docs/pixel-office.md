# Pixel office (Phase 1)

The Office tab is a pixel-art world. The agents from the database get bodies and walk around the
map. In Phase 1 their movement is a self-running demo (not yet driven by what the agent runtime
decides). The old card grid is still there as the **list view** (button in the top right).

## Files

| File | What it does |
| --- | --- |
| `ui/office/OfficeScreen.kt` | Screen: world by default, list view as fallback, HUD, selected-agent card |
| `ui/office/world/OfficeWorld.kt` | Canvas renderer, camera (pan and pinch), ~30 fps loop, sprites, bubbles, debug overlay |
| `ui/office/world/OfficeSim.kt` | Actors, activities, walking, the demo director (`decide`) |
| `ui/office/world/OfficeLayout.kt` | Waypoint graph and every spot (desks, kitchen, library, meeting, lounge, queue) |
| `ui/office/world/WorldAssets.kt` | Sprite sheet math, low-memory bitmap decoding |
| `ui/office/world/PixelUi.kt` | Palette and pixel-style panel, button, icon button |
| `ui/AgoriaNav.kt` | Passes `onOpenApprovals` into the office |

## Key facts about the art

- `office_tileset.png` is **not a tileset**. It is one pre-drawn picture of the whole office, so the
  renderer draws it as a single image. There is no tile map to maintain.
- `office_sprites.png` is 8 characters, 4 across and 2 down. Each is 3 columns (left step, stand,
  right step) by 4 rows (down, left, right, up) of 32 px cells.
- The image is stretched over an 800 x 800 "world". All coordinates in `OfficeLayout.kt` use that.

## Placing things: the debug overlay

Long-press the **THE OFFICE** title. You get:

- cyan lines and squares: the walking graph (agents only walk along these lines)
- orange rings: every spot where an agent can stand
- yellow box: your desk tap area
- red box: the map bounds the camera is clamped to
- a readout of the world `x`/`y` of wherever you tap

If an agent walks through a wall or stands on a desk, tap where they should be, then change the
matching `node(...)`/`spot(...)` numbers in `OfficeLayout.kt`. The first pass is placed by eye from
the picture, so expect to nudge some of them.

If characters look like they walk backwards or left and right are swapped, change `rowForDir`
in `WorldAssets.kt` (swap the 1 and the 2).

## Performance (built for a Helio G36 / 4 GB phone)

- One map bitmap, decoded without density scaling and capped at 2048 px on the long side.
  (Putting a PNG in `res/drawable` and decoding it normally scales it up on phones.)
- Redraw capped at about 30 fps and only while the screen is visible.
- Sprites are drawn at whole multiples of 32 px with nearest-neighbour filtering.
- Pathfinding is a tiny graph search (72 nodes), only when an agent picks a new destination.
- Name tags are measured once and cached.

## What comes next

2. Drive activity from the runtime (`AgentDecision.action` to a spot), real "!" for pending
   approvals, and a desk sheet with auto-approve per type.
3. Agent-to-agent `say` action, message table, real speech bubble text.
4. Remove the old tabs, polish, sound, onboarding.
