[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Geothermal Activity

Even when it is not erupting, a volcano remains a source of intense geothermal energy in real life. Typhon implements this continuous geothermal activity to enhance realism.

<img src="https://github.com/user-attachments/assets/9c033765-aac8-463b-bbee-3d03d5ff254c" width="400" />

## Geothermal Heat

Volcanic heat gradually dissipates through the ground, creating heated zones around vents.

**Heat Distribution**:
- Intensity varies based on the [volcanic status](./status.md)
- Heat diminishes gradually with distance from vents
- Subsidiary vents create their own heat zones

The heat value can be checked with `/vol <name> heat` command (range: 0-1).

### Cooking with Geothermal Heat

Geothermal heat can cook food items:
- **Dormant Volcanoes**: Occasional cooking inside crater only
- **Minor Activity**: Unreliable cooking inside crater
- **Major Activity**: Reliable cooking in crater, occasional on flanks
- **Eruption Imminent/Erupting**: Entire volcano hot enough to cook food

**Usage**: Drop raw food items in areas with sufficient geothermal heat.

## Fumaroles

Fumaroles are openings in the earth's crust where volcanic gases and steam escape, creating landscapes of steam and mineral deposits.

Fumaroles visually signal a volcano's activity level:

### Fumarole Activity by Volcanic Status

| Volcanic Status | Crater Fumaroles | Flank Fumaroles | Appearance |
|-----------------|------------------|-----------------|------------|
| **Extinct** | None | None | No activity |
| **Dormant** | Occasional | None | Small wisps of steam inside crater |
| **Minor Activity** | Sustained | Occasional | Steady steam from crater, occasional wisps on flanks |
| **Major Activity** | Abundant | Sustained | Dense steam in crater, visible steam vents on flanks |
| **Eruption Imminent/Erupting** | Pervasive | Abundant | Steam covers the entire crater, widely distributed vents on flanks |

### Environmental Effects

Fumaroles aren't just visual - they impact their surroundings:

- **Dormant**: Harmless steam emissions
- **Minor Activity**: 
  - Inside crater: Steam with occasional volcanic gases
  - On flanks: Mostly harmless steam
- **Major Activity**: 
  - Concentrated volcanic gases that can damage nearby vegetation
  - Multiple Steam plumes visible
  - Mob spawns are deterred

These effects (burning, less mob spawns and evaporates) can be disabled by setting the [`config_nodes`](./config_nodes.md)

## Volcanic Gases

Volcanic gases are deadly emissions that can harm living creatures and reshape environments.

### Environmental Impact

- **Plant Life**: Gases gradually kill trees and plants with repeated exposure
  - Trees lose their leaves
  - Plants wither and die

- **Player and Mob Effects**: Gases affect living creatures
  - **Chemical Burns**: Damage from acidic gases (reduced with fire-resistance)
  - **Nausea**: Disorientation from toxic gas exposure
  - **Poison**: Systemic effects of volcanic gas inhalation
  - **Copper Golem Oxidation**: Copper golems oxidize faster in volcanic gas

- **Equipment Damage**: Corrosive gases damage tools
  - Wooden tools break down faster in gas-rich environments
  - Iron and copper tools corrode when exposed to volcanic gases
