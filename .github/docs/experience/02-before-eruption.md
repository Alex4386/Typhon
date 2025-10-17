[< Return to Typhon Docs](/DOCS.md)
[< Return to Experience Guides](index.md)

# Before Eruption: Warning Signs and Hazards

Your volcano doesn't just sit quietly until eruption. It shows increasing signs of activity as it builds toward an eruption. Understanding these warning signs will help you stay safe and know when danger is approaching.

## Volcanic Status Progression

Volcanoes progress through activity states. Each state brings new phenomena and increased danger:

```
Extinct → Dormant → Minor Activity → Major Activity → Eruption Imminent → Erupting
```

Check your volcano's status:
```
/volcano <volcano_name> status
```

![Volcano status progression](placeholder-status-progression.png)

## Extinct Volcanoes

**What you'll see:**
- No activity whatsoever
- Vegetation covers everything, even the crater
- Safe to build near

**Threats:** None

![Extinct volcano](placeholder-extinct.png)

## Dormant Volcanoes

**What you'll see:**
- Occasional faint steam from crater
- Mild heat in the crater area
- Vegetation everywhere except crater interior

**Threats you'll encounter:**
- ⚠️ **Heat zones in crater** - Can occasionally cook dropped food items
- Very low danger, mostly safe

![Dormant volcano](placeholder-dormant.png)

**Experience it:**
```
/volcano <volcano_name> mainvent status dormant
```

Drop raw food in the crater and wait - it might cook!

## Minor Activity

Now things start getting interesting. The volcano is waking up.

**What you'll see:**
- 💨 **Sustained steam from crater**
- 💨 **Occasional fumaroles on flanks**
- Visible heat shimmer effects

**Threats you'll encounter:**
- ⚠️ **Heat damage near crater** - Standing too close to the vent will burn you
- ⚠️ **Volcanic gases** - Occasional gas clouds that cause:
  - 🤢 Nausea effect
  - ☠️ Poison damage
  - 🔧 Tool corrosion (wooden tools break faster)
- ⚠️ **Vegetation stress** - Upper flank plants start showing damage

![Minor activity volcano](placeholder-minor-activity.png)

**Experience it:**
```
/volcano <volcano_name> mainvent status minor_activity
```

Stand near the crater and watch for gas emissions. You'll feel the effects!

## Major Activity

The volcano is becoming dangerous. This is your warning to evacuate.

**What you'll see:**
- 💨💨💨 **Dense steam throughout crater**
- 💨 **Multiple fumaroles on flanks**
- 🔥 **Water in crater evaporates rapidly**
- 💀 **Upper flank vegetation dying**

**Threats you'll encounter:**
- 🔥 **Widespread heat zones** - Burns throughout volcanic area
- ☠️ **Intense volcanic gases** - Sustained gas emissions cause:
  - Chemical burns (fire resistance helps but doesn't eliminate)
  - Nausea and poison
  - Faster tool corrosion on iron and copper tools
  - Death to nearby trees and plants
- ⚠️ **No ecological succession** - Nothing grows while volcano is active

![Major activity volcano](placeholder-major-activity.png)

**Experience it:**
```
/volcano <volcano_name> mainvent status major_activity
```

⚠️ **Warning:** This is genuinely dangerous! Have healing items ready.

## Eruption Imminent

**The volcano is about to erupt. Get to safety NOW!**

**What you'll see:**
- 🌋 **Crater filled with intense heat and activity**
- 💨💨💨 **Maximum fumarole activity everywhere**
- 🔥 **Incandescent glow visible in crater**
- ☠️ **All vegetation near volcano dying**

**Threats you'll encounter:**
- 🔥🔥🔥 **Extreme heat damage throughout area** - Standing anywhere near the volcano burns you
- ☠️☠️ **Maximum volcanic gas concentration** - Lethal gas clouds everywhere
- 🚫 **Complete ecological shutdown** - Nothing survives this
- ⚠️ **Imminent eruption** - Could start erupting at any moment

![Eruption imminent](placeholder-eruption-imminent.png)

**Experience it:**
```
/volcano <volcano_name> mainvent status eruption_imminent
```

⚠️ **DANGER:** Do not stay near the volcano! This is extremely hazardous.

## Geothermal Features in Detail

### Fumaroles (Steam Vents)

Openings where volcanic gases and steam escape.

![Fumaroles](placeholder-fumaroles.png)

**Where you'll find them:**
- Inside crater (all activity levels)
- On flanks (minor activity and above)
- More numerous as activity increases

**Dangers:**
- Steam itself is mostly harmless
- BUT gas mixed with steam can poison you
- Indicates heat zones nearby

### Volcanic Gases

Deadly emissions from the volcanic system.

![Volcanic gases](placeholder-gases.png)

**Effects on you:**
- 🤢 **Nausea** - Disorientation and screen wobble
- ☠️ **Poison** - Health damage over time
- 🔥 **Chemical burns** - Direct damage (fire resistance reduces but doesn't prevent)

**Effects on your equipment:**
- 🪓 **Wooden tools** - Break down faster
- ⚔️ **Iron tools** - Corrode faster
- 🔧 **Copper tools** - Corrode faster
- 🤖 **Copper golems** - Oxidize rapidly

**Effects on environment:**
- 🌳 **Trees lose leaves** - Repeated gas exposure kills trees
- 🌱 **Plants die** - Vegetation cannot survive sustained gas exposure
- 🐔 **Mobs avoid area** - Animals won't spawn in gas zones

### Geothermal Heat Zones

Areas of elevated temperature from underground magma.

![Heat zones](placeholder-heat-zones.png)

**How to detect:**
```
/volcano <volcano_name> heat
```

Heat value ranges from 0 (cool) to 1 (extreme heat).

**Heat effects by activity level:**

| Status | Crater | Flanks | Effects |
|--------|--------|--------|---------|
| **Dormant** | Low heat | None | Occasional cooking in crater |
| **Minor Activity** | Medium heat | Low heat | Reliable cooking in crater, occasional on flanks |
| **Major Activity** | High heat | Medium heat | Cooking everywhere, heat damage starts |
| **Eruption Imminent** | Extreme heat | High heat | Cooking everywhere, significant heat damage |

**Practical uses:**
- 🍖 **Cooking** - Drop raw food in hot areas to cook it automatically
- ⚠️ **Navigation** - Avoid high heat zones to stay safe

**Dangers:**
- 🔥 Damage to players and mobs
- 💧 Water evaporation
- 🌱 Vegetation death

## Progression Timeline

**Understanding the buildup:**

1. **Days of calm** - Dormant volcano, safe to explore
2. **First signs** - Minor activity, occasional steam and heat
3. **Building pressure** - Major activity, widespread geothermal phenomena
4. **Final warning** - Eruption imminent, extreme conditions
5. **ERUPTION** - All hazards combined plus lava, bombs, and ash

## Safety Guidelines

### Safe Observation Distances

**Dormant/Minor Activity:**
- ✅ Safe to work on flanks
- ⚠️ Caution in crater

**Major Activity:**
- ⚠️ Caution on flanks
- ❌ Stay out of crater
- ✅ Safe beyond volcano base

**Eruption Imminent:**
- ❌ Stay away from volcano entirely
- ✅ Observe from far distance only

### Protective Measures

**Fire Resistance:**
- ✅ Reduces heat damage
- ✅ Reduces chemical burn damage
- ❌ Does NOT eliminate all damage
- ❌ Does NOT prevent poison/nausea from gases

**Building Safety:**
- Build observation platforms beyond heat zones
- Use non-flammable materials
- Have escape routes planned
- Keep healing items ready

## What's Next?

Now that you understand the warning signs, prepare for:
- **[During Eruption](03-during-eruption.md)** - The full fury of volcanic threats
- **[After Eruption](04-post-eruption.md)** - Recovery and rebuilding

Remember: These pre-eruption phenomena are warnings. When you see eruption imminent status, the volcano is about to unleash its full destructive power. Be prepared!
