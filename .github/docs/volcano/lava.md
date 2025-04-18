[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Lava: The Molten Heart of Volcanoes

Lava is the lifeblood of a volcano, defining its shape, behavior, and hazards. From the fast-flowing rivers of Hawaii's Kilauea to the thick, slow-moving domes of Mount St. Helens, lava comes in many forms in nature.

In Typhon, lava isn't just a simple flowing block - it's a dynamic system that mimics real volcanic processes.

## Pillow Lava: The Underwater Art of Volcanoes

In nature, when hot lava meets cold ocean water, something magical happens. The sudden cooling creates bulbous, pillow-shaped formations as the outer surface hardens while the inside remains molten. These "pillows" stack upon each other, creating distinctive underwater volcanic landscapes seen at hotspots like Hawaii and Iceland.

| In Nature | In Typhon |
|:---------:|:--------:|
| ![Image](https://github.com/user-attachments/assets/5926e99c-3b70-4d60-9dee-22c086c96e7b) | ![Pillow Lava in Typhon](/.github/docs/volcano/assets/pillow_lava.png) |

Typhon recreates this natural wonder with remarkable accuracy:
- Magma blocks appear when lava flows underwater, simulating the characteristic pillow shapes
- The "pillows" branch in multiple directions as they would in nature
- Each pillow leaves a trail of cooled volcanic rock, just as real pillow lava solidifies into distinctive formations

## Silica Content: The Chemistry Behind the Show

In the real world, a volcano's drama depends largely on its silica content. Low-silica lava flows like water (think Hawaii), while high-silica lava barely moves at all (think Mount St. Helens' lava dome).

### Types of Lava in Nature and Typhon

| Lava Type | Silica % | Real World Example | In-Game Behavior | Cooling Products |
|-----------|----------|-------------------|------------------|------------------|
| **Ultramafic** | <41% | Komatiite lava (ancient) | Extremely fluid | DEEPSLATE, COBBLED_DEEPSLATE, NETHERRACK |
| **Mafic** | 41-45% | Hawaiian volcanoes | Very fluid | BASALT, POLISHED_BASALT, DEEPSLATE |
| **Basaltic** | 45-52% | Iceland's lava fields | Fluid, extensive flows | BASALT, POLISHED_BASALT |
| **Basaltic-Andesitic** | 52-57% | Galapagos volcanoes | Moderately fluid | ANDESITE, TUFF, BASALT, POLISHED_BASALT |
| **Andesitic** | 57-63% | Cascade Range volcanoes | Thick, moderate flows | ANDESITE, TUFF |
| **Andesitic-Dacitic** | 63-68% | Mt. Unzen (Japan) | Thick, limited flows | ANDESITE, TUFF, QUARTZ_BLOCK, OBSIDIAN, GRANITE |
| **Dacitic** | 68-72% | Mt. St. Helens | Very thick, short flows | STONE, OBSIDIAN, QUARTZ_BLOCK, AMETHYST_BLOCK, GRANITE |
| **Rhyolitic** | >72% | Yellowstone | Extremely viscous | STONE, OBSIDIAN, QUARTZ_BLOCK, AMETHYST_BLOCK, GRANITE |

> [!TIP]  
> Curious about the technical implementation? Check the [VolcanoComposition.java](/src/main/java/me/alex4386/plugin/typhon/volcano/VolcanoComposition.java) source code for details.

### Viscosity: How Lava Flows

In nature, silica is like glue - the more a lava has, the stickier and slower it becomes:

- **Low-Silica Lava (Basaltic)**: Forms rivers of lava that can travel for miles, creating vast lava fields
- **Medium-Silica Lava (Andesitic)**: Creates shorter, thicker flows that build steeper volcanoes
- **High-Silica Lava (Rhyolitic)**: Barely flows at all, often forming lava domes that grow upward instead of outward

Typhon faithfully recreates these behaviors:
- Low-silica lava flows farther and faster, generating new source blocks as it travels to simulate the runny nature
- High-silica lava moves slower and shorter distances, creating steeper volcanic structures

### Mineral Deposits: Volcanic Treasure

In the real world, volcanic activity is responsible for many valuable mineral deposits. As magma cools, various minerals crystallize and concentrate.

Typhon simulates this natural process:
- Lava flows have a chance to form ore blocks when cooling
- Different lava compositions create different types of ores
- To maintain realism and prevent "farming" of these resources, the [Primary Succession](./succession.md) system gradually removes surface ores over time

## Gas Content: The Explosive Factor

The explosive power of real volcanoes largely depends on their gas content. Gas-rich magma that can't easily release pressure tends to create violent eruptions, while gas-poor magma typically produces gentler eruptions.

In Typhon, higher gas content means:
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


