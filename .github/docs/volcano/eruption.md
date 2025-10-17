[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Eruptions

Volcanic eruptions occur when molten rock, gas, and ash escape from the magma chamber to the surface. Typhon simulates realistic volcanic behavior based on real-life volcanoes.

## Controlling Your Volcano's Fury

> [!NOTE]  
> These commands refer to the main vent. For controlling subsidiary vents, see the [Eruption Vents](./vents.md#commands) documentation.

To start or stop a volcanic eruption:  
`/volcano <volcano_name> mainvent <start|stop>`

## Eruption Styles

Eruptions are classified into different styles based on their explosivity, lava composition, and eruptive character. Typhon simulates these styles to create realistic volcanic behavior:

| Hawaiian | Strombolian | Vulcanian |
|:--------:|:-----------:|:---------:|
| ![Hawaiian](/.github/docs/volcano/assets/hawaiian.png) | ![Strombolian](/.github/docs/volcano/assets/strombolian.png) | ![Vulcanian](/.github/docs/volcano/assets/vulcanian.png) |

### Hawaiian Eruptions

Characterized by fluid, low-viscosity basaltic lava with minimal explosivity. These eruptions create broad, gently sloping shield volcanoes.

**Features**:
- Fluid lava flows that travel great distances
- Gentle lava fountains from the main vent
- Shield-shaped volcanoes with broad, gently sloping sides
- "Rootless cones" can form in the slopes

### Strombolian Eruptions

Characterized by regular, moderate explosions that send incandescent cinder, bombs, and spatter into the air. These eruptions gradually build steep cinder cones.

**Features**:
- Moderate lava fountains
- Nighttime displays of glowing [volcanic bombs](bombs.md)
- Cone-building eruptions that construct steeper stratovolcanoes
- Cinder and spatter accumulation around the vent

### Vulcanian Eruptions

Characterized by thick, viscous magma that produces powerful explosions. Blasting volcanic bombs, ash, and pumice. 

**Features**:
- Powerful explosions that shoot material high above the volcano
- Significant [ash clouds](ash.md#ash-plumes) and [pyroclastic flows](ash.md#pyroclastic-flows)
- Large [volcanic bombs](bombs.md) that can damage structures
- Steep-sided volcanic cones

## Special Eruption Styles

Certain environmental conditions automatically trigger special eruption styles:

| Surtseyan | Pilinian |
|:--------:|:-----------:|
| ![Surtseyan](/.github/docs/volcano/assets/surtseyan.png) | ![Pilinian](/.github/docs/volcano/assets/pilinian.png) |


### Surtseyan Eruptions

Occur when magma erupts through water, creating distinctive jets of steam, ash, and volcanic fragments through water-magma interaction.

**Triggered automatically when**:
- The volcano's summit is below sea level
- The crater is filled with water

**Features**:
- Steam-driven explosions
- Wet ash deposition via ash cloud formation
- Gradual landform building primarily with ash and lava, building to water surface

### Plinian Eruptions

The most powerful and destructive eruptions, featuring enormous ash plumes that reaches the sky and widespread pyroclastic flows.

**Triggered automatically during**:
- [Caldera formation](./caldera.md) events
- [Lateral blast](./lateral_blast.md) scenarios

**Features**:
- Massive ash plumes
- Even more widespread pyroclastic flows
- Covering extensive areas with ash trails several blocks deep

### Lava Dome Eruptions

Viscous lava oozes from the vent to form a dome structure that grows over time. When the dome reaches a critical size, it can collapse or explode, triggering pyroclastic flows.


## Setting Your Eruption Style

To change your volcano's eruption style:
`/volcano <volcano_name> mainvent style <style>`  

Where `<style>` is one of: `hawaiian`, `strombolian`, or `vulcanian`

> [!TIP]  
> When you set an eruption style, Typhon automatically configures the appropriate [silica content](./lava.md#silica-content) and other [technical parameters](./config_nodes.md) to create realistic behavior for that eruption type. This is equivalent to running `/volcano <volcano_name> mainvent config erupt:autoconfig confirm`.

## The Volcano's Base

The `baseY` parameter defines the volcano's foundation level:

- Reference point for vertical growth calculations for cone building (i.e. strombolian/vulcanian eruptions)
- Used for volcanic bomb trajectory calculations
- Ensures realistic behavior as the cone grows

> [!NOTE]
> When changing to `strombolian` or `vulcanian` eruption styles, `baseY` is automatically adjusted based on current cone height.

To manually reset the base height:
`/volcano <volcano_name> mainvent config bombs:resetBaseY`
