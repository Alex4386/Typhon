[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Lava

Lava is the core eruption mechanic, defining volcano shape, behavior, and hazards. Typhon simulates lava as a dynamic system based on real volcanic processes.

## Pillow Lava

When hot lava meets cold water, rapid cooling creates bulbous, pillow-shaped formations as the outer surface hardens while the inside remains molten.

| In Nature | In Typhon |
|:---------:|:--------:|
| ![Image](https://github.com/user-attachments/assets/5926e99c-3b70-4d60-9dee-22c086c96e7b) | ![Pillow Lava in Typhon](/.github/docs/volcano/assets/pillow_lava.png) |

**Features**:
- Magma blocks appear when lava flows underwater
- Pillows branch in multiple directions
- Cooled volcanic rock trails form distinctive patterns

## Silica Content

Silica content determines lava viscosity and flow behavior. Low-silica lava flows easily and far, while high-silica lava is highly viscous and barely flows.

### Lava Types

| Lava Type | Silica % | Flow Behavior | Cooling Products |
|-----------|----------|---------------|------------------|
| **Ultramafic** | <41% | Extremely fluid | DEEPSLATE, COBBLED_DEEPSLATE, NETHERRACK |
| **Mafic** | 41-45% | Very fluid | BASALT, POLISHED_BASALT, DEEPSLATE |
| **Basaltic** | 45-52% | Fluid, extensive flows | BASALT, POLISHED_BASALT |
| **Basaltic-Andesitic** | 52-57% | Moderately fluid | ANDESITE, TUFF, BASALT, POLISHED_BASALT |
| **Andesitic** | 57-63% | Thick, moderate flows | ANDESITE, TUFF |
| **Andesitic-Dacitic** | 63-68% | Thick, limited flows | ANDESITE, TUFF, QUARTZ_BLOCK, OBSIDIAN, GRANITE |
| **Dacitic** | 68-72% | Very thick, short flows | STONE, OBSIDIAN, QUARTZ_BLOCK, AMETHYST_BLOCK, GRANITE |
| **Rhyolitic** | >72% | Extremely viscous | STONE, OBSIDIAN, QUARTZ_BLOCK, AMETHYST_BLOCK, GRANITE |

> [!TIP]  
> Curious about the technical implementation? Check the [VolcanoComposition.java](/src/main/java/me/alex4386/plugin/typhon/volcano/VolcanoComposition.java) source code for details.

### Viscosity

Silica content determines lava viscosity:

- **Low-Silica (Basaltic)**: Flows far and fast, creating vast lava fields
- **Medium-Silica (Andesitic)**: Creates shorter, thicker flows and steeper volcanoes
- **High-Silica (Rhyolitic)**: Minimal flow, forms lava domes that grow upward

**Implementation**:
- Low-silica lava generates new source blocks as it travels, simulating fluid behavior
- High-silica lava moves slower and shorter distances, creating steep structures

### Mineral Deposits

Volcanic activity concentrates minerals as magma cools and crystallizes.

**Features**:
- Lava flows have a chance to form ore blocks when cooling
- Different lava compositions create different ore types
- [Primary Succession](./succession.md) gradually removes surface ores over time to prevent resource farming

## Gas Content

Gas content determines eruption explosivity. Gas-rich magma creates violent eruptions, while gas-poor magma produces gentler eruptions.

**Effects of higher gas content**:
- More explosive eruptions
- Larger ash clouds
- More volcanic bombs
- Greater pyroclastic flow potential

## Managing Your Volcano's Lava

### Setting Silica Content

To adjust how fluid your volcano's lava is:
`/volcano <volcano_name> mainvent config lavaflow:silicaLevel <silica_content>`

The `<silica_content>` value (between 0 and 1) represents the percentage of silica:
- Lower values (0.40-0.50): Hawaiian-style fluid lava
- Medium values (0.55-0.65): Moderate flows
- Higher values (0.70+): Thick, barely-flowing lava

### Setting Gas Content

To adjust your volcano's explosive potential:
`/volcano <volcano_name> mainvent config level:gasContent <gas_content>`

The `<gas_content>` value (between 0 and 1) controls how explosive your eruptions will be:
- Lower values: More gentle, effusive eruptions
- Higher values: More violent, explosive eruptions

### Emergency Lava Control: Quickcool

For server administrators or in emergency situations, you can instantly solidify all lava from a volcano:
`/volcano <volcano_name> quickcool`

For just the main vent:
`/volcano <volcano_name> mainvent quickcool`

> [!WARNING]
> Using quickcool interrupts natural lava behavior and can result in:
> - Incomplete or unrealistic volcanic formations
> - Missing pillow lava structures in underwater areas
> - Disrupted ore formation processes
> - Irregular volcano shapes
>
> It's best reserved for emergency server management rather than normal gameplay.


