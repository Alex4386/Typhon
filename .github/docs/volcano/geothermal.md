[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Geothermal Activity: The Volcano's Breath

In nature, volcanoes are not just about dramatic eruptions - they continuously breathe and release energy through geothermal activity. From the steaming fumaroles of Yellowstone to the deadly gas emissions at Lake Nyos, these phenomena shape the volcanic landscape even during periods of dormancy.

![Image](https://github.com/user-attachments/assets/9c033765-aac8-463b-bbee-3d03d5ff254c)

## Geothermal Heat: The Hidden Energy

Beneath every volcano lies a massive heat reservoir. In real volcanoes, this heat gradually dissipates through the ground, creating unique environments.

In Typhon, this heat spreads realistically throughout your volcano:
- The intensity varies based on the [volcanic status](./status.md)
- Heat diminishes gradually as you move away from vents
- Subsidiary vents create their own heat zones, just as in nature

The heat value used in Typhon can be checked via running `/vol <name> heat` command, having range between `0` to `1`.

### Cooking with Earth's Energy

In geothermal areas like Iceland, people have cooked food using the planet's natural heat for centuries.

Experience this in Typhon:
- **Dormant Volcanoes**: Only inside the crater will you find enough heat to occasionally cook food
- **Minor Activity**: Cooking is possible but unreliable inside the crater
- **Major Activity**: Reliable cooking inside the crater, with occasional success on the flanks
- **Eruption Imminent/Erupting**: The entire volcano becomes hot enough to cook dropped food items

**How to use it**: Simply drop raw food items (use your drop key) in areas with sufficient geothermal heat!

## Fumaroles: The Volcano's Vents

Fumaroles are nature's pressure-release valves - openings in the earth's crust where volcanic gases and steam escape. At places like Valley of Geysers in Russia, these create otherworldly landscapes of steam and mineral deposits.

In Typhon, fumaroles visually signal a volcano's activity level:

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

## Volcanic Gases: The Invisible Danger

In the real world, volcanic gases are silent killers. The 1986 Lake Nyos disaster in Cameroon showed how deadly CO₂ emissions can be, while acidic gases like sulfur dioxide at volcanoes like Kilauea continuously reshape their environments.

Typhon recreates these dangerous natural phenomena:

### Environmental Impact

- **Plant Life**: Just as at Mount Merapi, gases gradually kill trees and plants with repeated exposure
  - Trees lose their leaves
  - Plants wither and die
  
- **Player and Mob Effects**: As at real volcanic sites, gases affect living creatures
  - **Chemical Burns**: Damage from acidic gases
  - **Nausea**: Disorientation from toxic gas exposure
  - **Poison**: Systemic effects of volcanic gas inhalation

- **Equipment Damage**: Volcanic gases are corrosive in reality and in Typhon
  - Wooden tools break down faster in gas-rich environments
  - Iron tools corrode when exposed to volcanic gases
