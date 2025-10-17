[< Return to Typhon Docs](/DOCS.md)
[< Return to Experience Guides](index.md)

# After Eruption: Recovery and Rebirth

The eruption has ended. Now you'll witness how volcanic landscapes transform and eventually return to life through natural processes.

![Post-eruption landscape](placeholder-post-eruption.png)

## The Immediate Aftermath

When the eruption stops, you're left with a dramatically changed landscape:

### What You'll See

**Volcanic deposits:**
- 🗿 **Cooled lava** - Black volcanic rock where lava flowed
- 🗿 **Tuff layers** - Ash deposits covering surfaces
- 🕳️ **Bomb craters** - Impact sites from volcanic bombs
- 🏔️ **New terrain** - The volcano has grown or changed shape
- 💎 **Ore deposits** - Minerals formed in cooling lava

**Destruction:**
- 🔥 **Burned areas** - Where heat and fire passed
- 🪵 **Coal blocks** - Trees carbonized by pyroclastic flows
- 💀 **Dead vegetation** - Plants killed by gases and heat
- 🏚️ **Damaged structures** - If you built too close

![Aftermath devastation](placeholder-aftermath.png)

### The Volcano's New State

After stopping an eruption, the volcano doesn't immediately go dormant:

```
/volcano <volcano_name> status
```

**Status progression after eruption:**
```
Erupting → Eruption Imminent → Major Activity → Minor Activity → Dormant
```

The volcano gradually cools down, with each stage taking time.

## Exploring the Aftermath

### Safe to Approach When:

✅ **Status is Minor Activity or lower**
✅ **No more heat damage in most areas**
✅ **Volcanic gases reduced**
✅ **All lava has cooled**

⚠️ **Still dangerous if:**
- Crater still has high heat
- Active fumaroles remain
- Uncooled lava pockets exist

### What to Look For

**New terrain features:**
- 🌋 **Volcanic cone growth** - The mountain has grown
- 🏔️ **New slopes** - Lava flows created new terrain
- 🕳️ **Impact craters** - From volcanic bombs
- 💎 **Ore formations** - Check cooled lava for ores

**Emergency cleanup:**
```
/volcano <volcano_name> quickcool
```
⚠️ Only for emergency server management - instantly solidifies all lava but disrupts natural formation

## Volcanic Minerals and Ores

### Ore Formation

As lava cools, minerals crystallize and concentrate.

![Ore deposits](placeholder-ore-deposits.png)

**Where to find ores:**
- In cooled lava flows
- At lava flow edges
- Where lava solidified quickly

**Types depend on lava composition:**
- Different volcanic compositions create different ore types
- Check cooled lava fields for valuable minerals

### The Succession Trade-off

⚠️ **Important:** Ore deposits on the surface will gradually be removed by ecological succession.

**Why?** To prevent infinite ore farming from repeated eruptions. This mimics natural weathering processes.

**If you want the ores:**
- Mine them before succession removes them
- Or disable succession (see below)

## Ecological Succession: Life Returns

Over time, the barren volcanic landscape will naturally recover through ecological succession - the process where life gradually reclaims the land.

![Succession process](placeholder-succession.png)

### How Succession Works

**The natural progression:**
```
Bare Rock → Weathered Rock → Soil Formation → Grass → Vegetation → Trees
```

This happens automatically over time, faster in some areas than others.

### Stage 1: Weathering and Erosion

The volcanic rock begins breaking down.

![Weathering stage](placeholder-weathering.png)

**What happens:**
- Wind, rain, and temperature crack rocks
- ⛈️ **Storms accelerate the process**
- Chemical reactions alter volcanic minerals
- Surface gradually softens

**How long:** Days to weeks (Minecraft time)

### Stage 2: Primary Succession

Pioneer species colonize the barren rock.

![Primary succession](placeholder-primary-succession.png)

**What happens:**
- 🌱 Grass blocks gradually appear on weathered materials
- Initial vegetation emerges
- Each plant generation builds more soil
- Succession spreads from edges inward

**Influenced by:**
- Distance from heat sources (farther = faster)
- Volcanic activity level (dormant = faster)
- Weather (storms speed it up)

### Stage 3: Secondary Succession

More complex life returns.

![Secondary succession](placeholder-secondary-succession.png)

**What happens:**
- Grass transitions to diverse vegetation
- 🌳 Trees begin growing
- Landscape returns to natural appearance
- Animal spawning returns

**Result:** The volcano looks like part of the natural landscape again

### Factors Affecting Succession

**Volcanic heat slows or prevents succession:**

| Distance from Heat | Succession Speed |
|-------------------|------------------|
| Crater center | None (too hot) |
| Upper flanks | Very slow |
| Lower flanks | Moderate |
| Beyond volcano | Normal |

**Volcanic activity pauses succession:**
- **Dormant/Minor Activity:** Succession progresses (except crater)
- **Major Activity:** Succession halted
- **Eruption Imminent/Erupting:** Succession completely stopped

**Weather accelerates succession:**
- ⛈️ Storms speed up weathering and erosion
- 🌧️ Rain helps plant growth

## Managing Succession

### Global Volcano Control

Enable or disable succession for entire volcano:
```
/volcano <volcano_name> succession <true|false>
```

**Disable if you want to:**
- Keep the raw volcanic landscape
- Preserve ore deposits
- Build on volcanic terrain
- Study the volcanic features

**Enable if you want:**
- Natural landscape recovery
- Vegetation to return
- Realistic healing process

### Per-Vent Control

Control succession around specific vents:
```
/volcano <volcano_name> mainvent config succession:enable <true|false>
```

Useful if you have multiple vents and want different behaviors.

### Manual Succession Tool

Speed up succession in specific areas:

1. **Enable the tool:**
```
/typhon succession enable
```

2. **Use the wooden shovel** - Right-click volcanic terrain to trigger succession

3. **Disable when done:**
```
/typhon succession disable
```

![Manual succession tool](placeholder-manual-succession.png)

**Use cases:**
- Speed up recovery in specific areas
- Create contrast between recovered and raw terrain
- Build artistic landscapes
- Prepare building sites

## Building on Volcanic Terrain

### When It's Safe

✅ **Safe to build after eruption when:**
- Volcano status is Dormant or Extinct
- All lava has cooled
- Heat zones have dissipated
- You're beyond the danger radius

### Where to Build

**Safest locations:**
- Beyond the volcano's base
- On high ground away from lava channels
- On pre-eruption terrain (not new lava flows)

**Risky but possible:**
- Lower flanks (if volcano is dormant)
- Cooled lava fields (watch for heat)

**Never build:**
- In or near crater
- In lava flow paths
- On uncooled lava

### Building Materials

**Recommended:**
- 🗿 Stone, cobblestone, deepslate
- 🟫 Obsidian (lava-proof)
- 🧱 Bricks, concrete
- 🟦 Prismarine, basalt

**Avoid:**
- 🪵 Wood (burns)
- 🧶 Wool (burns)
- 🍃 Leaves (burns)

## The Volcanic Lifecycle

Understanding the full lifecycle helps you plan:

### Complete Cycle

```
1. Creation → Dormant
2. Building activity → Minor Activity
3. Pre-eruption → Major Activity → Eruption Imminent
4. ERUPTION → Erupting status
5. Wind-down → Major → Minor → Dormant
6. Recovery → Succession begins
7. Eventually → Return to dormant state
```

### Repeating Eruptions

**Polygenetic volcanoes** (default) can erupt multiple times:
```
/volcano <volcano_name> mainvent genesis polygenetic
```

The volcano remains active and can erupt again, cycling through the stages.

**Monogenetic volcanoes** erupt only once:
```
/volcano <volcano_name> mainvent genesis monogenetic
```

After eruption, they become extinct and never erupt again.

## Long-Term Changes

### Permanent Landscape Alterations

What remains after succession completes:

**Permanent features:**
- 🏔️ New volcanic cone shape
- 🗿 Solidified lava flows (as basalt, andesite, etc.)
- 🕳️ Bomb craters
- 🌋 Altered crater shape
- 💎 Subsurface ore deposits

**Removed by succession:**
- 🗿 Surface ash/tuff layers
- 💎 Surface ore deposits
- 💀 Dead trees (replaced with new growth)
- 🔥 Burn marks

### Multiple Eruption Effects

Each eruption adds to the landscape:
- Cone grows taller
- Lava flows layer upon previous flows
- Volcanic edifice expands
- New deposits cover old ones

This is how real volcanoes build over time!

## Advanced: Catastrophic Aftermath

If you triggered special events, the aftermath is more dramatic:

### After a Lateral Blast

![Lateral blast aftermath](placeholder-lateral-blast-aftermath.png)

**Permanent changes:**
- 🏔️ Horseshoe-shaped crater
- ⬇️ Reduced volcano height
- 👉 Blast zone devastation in one direction
- 🗻 Asymmetrical volcano shape

**Recovery:** Succession can eventually revegetate the blast zone

### After Caldera Formation

![Caldera aftermath](placeholder-caldera-aftermath.png)

**Permanent changes:**
- 🕳️ Massive crater depression (much wider than original summit)
- 🌊 Possible caldera lake
- ⬇️⬇️ Major height reduction
- 🏔️ Steep caldera walls

**Recovery:** Crater floor may fill with water, succession on walls

## Quick Reference: Post-Eruption Checklist

**Safety check:**
- [ ] Check volcano status (should be Minor Activity or lower)
- [ ] Verify no active lava flows remain
- [ ] Check heat levels in areas you want to explore
- [ ] Wait for gases to dissipate

**Exploration:**
- [ ] Survey terrain changes
- [ ] Map new lava flows
- [ ] Check for ore deposits
- [ ] Document bomb craters
- [ ] Assess damage to structures

**Recovery decisions:**
- [ ] Enable/disable succession based on goals
- [ ] Use manual succession tool for targeted recovery
- [ ] Mine any ore deposits you want before succession removes them
- [ ] Plan building locations if desired

**Maintenance:**
- [ ] Set volcano to desired activity level
- [ ] Configure succession settings
- [ ] Prepare for next eruption cycle (if polygenetic)

## What's Next?

You've now experienced the complete volcanic cycle:
1. ✅ [Creating Your First Volcano](01-creating-your-first-volcano.md)
2. ✅ [Before Eruption: Warning Signs](02-before-eruption.md)
3. ✅ [During Eruption: The Threats](03-during-eruption.md)
4. ✅ [After Eruption: Recovery](04-post-eruption.md)

### Going Deeper

**For more advanced features:**
- [Multiple Vents](../volcano/vents.md) - Create complex volcanic systems
- [Volcano Builder](../volcano/builder.md) - Automatically construct realistic volcanoes
- [Lateral Blast](../volcano/lateral_blast.md) - Catastrophic sideways eruptions
- [Caldera Formation](../volcano/caldera.md) - Massive collapse events
- [Configuration](../volcano/config_nodes.md) - Fine-tune all parameters

### The Volcanic Experience

Typhon brings real volcanic processes to Minecraft:
- Real threats that require real respect
- Dynamic landscapes that evolve over time
- Natural recovery through succession
- Spectacular visual phenomena
- Educational and entertaining

Build wisely, observe safely, and enjoy the power of nature! 🌋
