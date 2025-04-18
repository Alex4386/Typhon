[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Eruptions: Nature's Most Spectacular Display

A volcanic eruption is Earth's most dramatic geologic event - the moment when molten rock, gas, and ash escape from the magma chamber to the surface. From the gentle lava lakes of Hawaii's Kilauea to the catastrophic explosion of Mount St. Helens in 1980, eruptions shape our planet's surface and have influenced human civilization throughout history.

Typhon brings these natural spectacles to Minecraft with unprecedented realism.

## Controlling Your Volcano's Fury

> [!NOTE]  
> These commands refer to the main vent. For controlling subsidiary vents, see the [Eruption Vents](./vents.md#commands) documentation.

To start or stop a volcanic eruption:  
`/volcano <volcano_name> mainvent <start|stop>`

## Eruption Styles: The Spectrum of Volcanic Behavior

In volcanology, eruptions are classified into different styles based on their explosivity, lava composition, and eruptive character. Typhon recreates the most important styles with remarkable accuracy:

| Hawaiian | Strombolian | Vulcanian |
|:--------:|:-----------:|:---------:|
| ![Hawaiian](/.github/docs/volcano/assets/hawaiian.png) | ![Strombolian](/.github/docs/volcano/assets/strombolian.png) | ![Vulcanian](/.github/docs/volcano/assets/vulcanian.png) |

### Hawaiian Eruptions: The Gentle Giants

**In Nature**: Named after the Hawaiian Islands' shield volcanoes, these eruptions feature fluid, low-viscosity basaltic lava with minimal explosivity. Kilauea's eruptions often create spectacular lava fountains that build broad, gently sloping shield volcanoes over time.

**In Typhon**: Hawaiian-style eruptions produce:
- Fluid lava flows that travel great distances
- Gentle lava fountains from the main vent
- Shield-shaped volcanoes with broad, gently sloping sides
- "Rootless cones" that form when lava flows over wet ground, just as seen in Iceland

### Strombolian Eruptions: Nature's Fireworks

**In Nature**: Named after Stromboli volcano in Italy, this style features regular, moderate explosions every few minutes. These eruptions send incandescent cinder, bombs, and spatter hundreds of meters into the air - the "lighthouse of the Mediterranean" has been erupting almost continuously for over 2,000 years.

**In Typhon**: Strombolian eruptions create:
- Moderate lava fountains
- Spectacular nighttime displays of glowing [volcanic bombs](bombs.md)
- Cone-building eruptions that gradually construct steeper stratovolcanoes
- Cinder and spatter that builds up around the vent

### Vulcanian Eruptions: The Explosive Force

**In Nature**: Named after Vulcano in Italy's Aeolian Islands, these powerful eruptions feature thick, viscous magma that leads to significant explosions. Mount Vesuvius has produced vulcanian eruptions, blasting volcanic bombs, ash, and pumice high into the atmosphere.

**In Typhon**: Vulcanian eruptions generate:
- Powerful explosions that shoot material high above the volcano
- Significant [ash clouds](ash.md#ash-plumes) and [pyroclastic flows](ash.md#pyroclastic-flows)
- Large [volcanic bombs](bombs.md) that can damage structures
- Steep-sided volcanic cones

### Special Eruption Styles

Just as in nature, certain environmental conditions trigger special eruption styles in Typhon:

| Surtseyan | Pilinian |
|:--------:|:-----------:|
| ![Surtseyan](/.github/docs/volcano/assets/surtseyan.png) | ![Pilinian](/.github/docs/volcano/assets/pilinian.png) |


#### Surtseyan Eruptions: The Sea-Born Volcano

**In Nature**: Named after Surtsey, which emerged from the ocean near Iceland in 1963, these eruptions occur when magma erupts through water. The water-magma interaction creates distinctive "rooster tail" jets of steam, ash, and volcanic fragments.

**In Typhon**: Triggered automatically when:
- The volcano's summit is below sea level
- The crater is filled with water

These eruptions feature steam-driven explosions, wet ash deposition, and unique landforms that gradually build up to the water surface.

#### Plinian Eruptions: History's Most Catastrophic Volcanic Events

**In Nature**: Named after Pliny the Younger, who described the 79 CE eruption of Mount Vesuvius, these cataclysmic eruptions feature enormous columns of ash that can reach the stratosphere. The 1980 Mount St. Helens eruption and the 1991 Mount Pinatubo eruption were plinian events that affected global climate.

**In Typhon**: Triggered automatically during:
- [Caldera formation](./caldera.md) events
- [Lateral blast](./lateral_blast.md) scenarios

These represent the most powerful and destructive eruptions possible, featuring massive ash columns, widespread pyroclastic flows, and significant landscape alteration.

## Setting Your Eruption Style

To change your volcano's eruption style:
`/volcano <volcano_name> mainvent style <style>`  

Where `<style>` is one of: `hawaiian`, `strombolian`, or `vulcanian`

> [!TIP]  
> When you set an eruption style, Typhon automatically configures the appropriate [silica content](./lava.md#silica-content) and other [technical parameters](./config_nodes.md) to create realistic behavior for that eruption type. This is equivalent to running `/volcano <volcano_name> mainvent config erupt:autoconfig confirm`.

## The Volcano's Base: Foundation of Fire

In real volcanoes, the "base level" refers to the foundation from which the volcanic edifice grows. In Typhon, the `baseY` parameter serves a similar function:

- It defines the reference point for the volcano's vertical growth
- It helps calculate proper trajectories for volcanic bombs
- It ensures realistic behavior as the volcanic cone grows higher

> [!NOTE]  
> When changing to `strombolian` or `vulcanian` eruption styles, the `baseY` is automatically adjusted based on the current cone height to ensure realistic eruptive behavior.

To manually reset the base height:  
`/volcano <volcano_name> mainvent config bombs:resetBaseY`
