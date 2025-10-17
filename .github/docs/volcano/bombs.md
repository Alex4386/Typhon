[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Bombs

During explosive eruptions, volcanoes launch solid projectiles called volcanic bombs - molten lava fragments that cool and solidify during flight, forming distinctive aerodynamic shapes.

Typhon implements volcanic bombs with impressive realism, simulating their ejection with `FallingBlock` entities and impact effects,

## Implementation

Typhon simulates volcanic bombs using Minecraft's FallingBlock entities:

- Glowing fragments are ejected high above the volcano's vent with blackbody coloring
- Bombs follow realistic parabolic trajectories using physics calculations
- Each bomb can damage structures and create impact craters upon landing
- Bombs can start fires and release residual lava on impact

<img src="https://github.com/user-attachments/assets/fb7f6bda-fa68-49a5-9e07-984d376dcb7f" width="300" />

## Size Variation

Volcanic bomb size varies depending on eruption style:

- **Strombolian Eruptions**: Moderate-sized bombs (1-2 blocks)
- **Vulcanian/Plinian Eruptions**: Massive bombs (3-5 blocks)

Bomb size and frequency correlate with the volcano's explosion power, mimicking the relationship between eruption intensity and ejected material.

## Impact Phenomena

Volcanic bombs create distinctive impact features when they hit the ground:

### Crater Formation
- Bombs create size-appropriate craters on impact
- Crater depth and width scale with bomb size and impact velocity
- Surrounding terrain is deformed realistically

### Cooling and Solidification
Bombs are often still partially molten upon landing:
- Upon impact, residual lava may ooze from the bomb's interior
- Lava cools into volcanic rock types according to the volcano's [lava composition](lava.md#silica-content)
- Creates realistic impact sites with cooled volcanic formations
