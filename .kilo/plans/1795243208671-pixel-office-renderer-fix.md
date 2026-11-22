# Fix Broken Pixel-Office Renderer

## Problem Summary
The current `OfficeWorld.kt` has a broken renderer causing:
1. Office map squashed into a square (704×1664 drawn as square)
2. Furniture atlas drawn whole instead of individual sprites
3. No furniture sprites from `OfficeMapData.OBJECTS` rendered
4. Agents appear in empty dark area below the map
5. Missing bubble types (THINK, ERROR, ZZZ)
6. Tap handler only works when tapping desk hotspot
7. Desk alert is a stroked square instead of bobbing "!"
8. No selected hotspot outline
9. Wrong camera defaults and sprite sizing

## Root Cause Analysis (from code inspection)

| Issue | Location | Current Code | Required Fix |
|-------|----------|--------------|--------------|
| Base map square | `OfficeWorld.kt:213-222` | `dstSize = IntSize(side, side)` where `side = worldW * scale` | Use `worldW * scale` × `worldH * scale` |
| Furniture atlas drawn whole | `OfficeWorld.kt:225-237` | Draws entire `office_pixel_objects.png` | Draw individual sprites from `OBJECTS` array |
| No furniture sprites | Missing | No iteration over `OfficeMapData.OBJECTS` | Iterate 7-int entries: srcX, srcY, w, h, dstX, dstY, sortY |
| No y-sorting merge | Missing | Only agents sorted by y | Merge furniture (by sortY) and agents (by foot y) |
| spritePx formula | `OfficeWorld.kt:104` | `32f * (scale * SPRITE_WORLD / 32f).roundToInt().coerceIn(1, 8)` | `32f * max(2, scale.roundToInt()).coerceAtMost(8)` |
| Camera defaults | `OfficeWorld.kt:71-72` | `cy = 300f`, `DEFAULT_SCALE = 2.2f` | `cy = 420f`, `DEFAULT_SCALE = 2f` |
| Tap handler gated | `OfficeWorld.kt:190-198` | Only checks hotspots if in desk hotspot | Check all hotspots on any tap |
| Missing bubbles | `OfficeWorld.kt:297-300` | Only ALERT, TALK handled | Add THINK, ERROR, ZZZ |
| Desk alert wrong | `OfficeWorld.kt:239-248` | Stroked square at desk center | Bobbing amber "!" at deskHotspot (ax, ay) |
| No hotspot outline | Missing | No code for selected hotspot | Draw amber outline around selectedHotspotId |

## Data Structures (Already Correct)

- `OfficeMapData.OBJECTS`: `IntArray` with 7 ints per object (srcX, srcY, w, h, dstX, dstY, sortY), already sorted by sortY
- `OfficeMapData.HOTSPOTS`: List of `HotspotDef` with rect, ax, ay for "!" position
- `OfficeLayout.worldW = 704f`, `worldH = 1664f` (22 × 32, 52 × 32)
- `Bubble` enum: NONE, ALERT, TALK, THINK, ERROR, ZZZ (all defined in `OfficeSim.kt`)

## Implementation Plan

### 1. Replace `OfficeWorld.kt` with Corrected Renderer

Create a new `OfficeWorld.kt` that implements all the fixes:

#### CameraState Fixes
- `DEFAULT_SCALE = 2f`
- `cx = worldW / 2f` (352f)
- `cy = 420f`
- `minScale = viewW / worldW`
- Remove `SPRITE_WORLD` constant
- Fix `spritePx()` = `32f * max(2, scale.roundToInt()).coerceAtMost(8)`

#### Canvas Draw Block
```kotlin
// a) Base map at correct aspect ratio
val baseImage = map
if (baseImage != null) {
    val l = sx(0f)
    val t = sy(0f)
    val r = sx(worldW)
    val b = sy(worldH)
    drawImage(baseImage, srcOffset=Zero, srcSize=full, 
              dstOffset=(l,t), dstSize=(r-l, b-t), FilterQuality.None)
}

// b) Furniture + agents merged by y (back to front)
// OBJECTS: 7 ints per object (srcX, srcY, w, h, dstX, dstY, sortY) - already y-sorted
// Actors: sort by foot y (actor.y)
// Merge: while either has items, draw object if objSortY <= actorY, else actor

// c) Agent sprite size: spritePx() = 32f * max(2, scale.roundToInt()).coerceAtMost(8)

// d) Camera already fixed in CameraState

// e) Tap handler: find hotspot anywhere on map
// f) Draw all bubbles: ALERT, ERROR, TALK, THINK, ZZZ
//    Desk alert: bobbing "!" at deskHotspot (ax, ay)
// g) Draw amber outline around selected hotspot
```

#### Bubble Drawing Functions
- `drawAlert(cx, headY, t)` - amber "!" (already exists)
- `drawError(cx, headY, t)` - red "!" (new)
- `drawTalk(cx, headY, t)` - three dots (already exists)
- `drawThink(cx, headY, t)` - dots with trail (new)
- `drawZzz(cx, headY, t)` - ZZZ bubble (new)
- `drawDeskAlert(camera, deskHotspot, elapsed)` - bobbing "!" at (ax, ay) (new)

#### Hotspot Outline
- Draw amber 2dp stroke rect around `selectedHotspotId` hotspot's rect in screen space

### 2. Verify Resource Files
- ✅ `res/drawable-nodpi/office_pixel_base.png` (704×1664) - exists
- ✅ `res/drawable-nodpi/office_pixel_objects.png` (512×451) - exists
- ✅ `res/drawable/office_sprites.png` - exists
- ❌ `res/drawable/office_tileset.png` - doesn't exist (already deleted)

### 3. Keep Unchanged
- `OfficeViewModel.kt` - do not modify
- `OfficeScreen.kt` - already passes correct callbacks (`onSelect`, `onHotspot`, `onNavigate`)
- `OfficeMapData.kt` - generated, do not hand-edit
- `OfficeLayout.kt` - correct
- `OfficeSim.kt` - correct
- `Signals.kt` - correct
- `tools/office_gen/*` - generators, keep as-is

## Validation Criteria
1. **Build passes**: `Build Debug & Release APK` succeeds
2. **Visual verification**:
   - Whole office is one tall map filling screen (not squashed)
   - No dark empty area below map
   - Desks, shelves, racks, plants align with base image floor spots
   - Agents sit behind desks with legs hidden
3. **Interaction verification**:
   - Tapping each room opens its card (War room, Library, Web room, Mail, Server room, Workshop, Finance, Notice board)
   - Tapping boss desk opens desk panel
   - Selected hotspot shows amber outline
   - All bubble types render (ALERT, ERROR, TALK, THINK, ZZZ)
   - Desk alert shows bobbing "!" at desk hotspot (ax, ay)

## Files to Modify
- `app/src/main/java/com/agoria/app/ui/office/world/OfficeWorld.kt` - **complete replacement**

## Files to Verify (No Changes)
- `app/src/main/java/com/agoria/app/ui/office/OfficeScreen.kt`
- `app/src/main/java/com/agoria/app/ui/office/OfficeViewModel.kt`
- `app/src/main/java/com/agoria/app/ui/office/world/OfficeMapData.kt`
- `app/src/main/java/com/agoria/app/ui/office/world/OfficeLayout.kt`
- `app/src/main/java/com/agoria/app/ui/office/world/OfficeSim.kt`
- `app/src/main/java/com/agoria/app/ui/office/world/Signals.kt`
- `app/src/main/java/com/agoria/app/ui/office/world/WorldAssets.kt`
- `app/src/main/java/com/agoria/app/ui/office/world/PixelUi.kt`

## Risk Mitigation
- The renderer rewrite is isolated to `OfficeWorld.kt` - no other files depend on its internals
- CameraState API remains compatible (same public methods)
- OfficeWorld composable signature unchanged
- All data structures (OBJECTS, HOTSPOTS, SPOTS) already correct and used
- Generator tools not modified - ensures future map regenerations work

## Implementation Notes
The fix implements the exact algorithm specified in the requirements:
- Coordinate transforms: `sx(wx) = (wx - cx) * scale + viewW/2`, `sy(wy) = (wy - cy) * scale + viewH/2`
- Base map: dst rect from (sx(0), sy(0)) to (sx(worldW), sy(worldH))
- Furniture: iterate OBJECTS 7 ints at a time, draw from atlas at dstX,dstY with srcX,srcY,w,h
- Merge: two-pointer merge of OBJECTS (by sortY) and actors (by actor.y)
- spritePx: minimum 64px (32*2), maximum 256px (32*8), scales with zoom
- Camera: centered at worldW/2, 420f vertically, minScale fits width
- Taps: hit test agents first, then find containing hotspot by rect.contains(), pick smallest area
- Bubbles: all 5 types rendered with appropriate visuals
- Desk alert: bobbing amber "!" at deskHotspot.ax, deskHotspot.ay
- Selected hotspot: amber 2dp stroke outline in screen space