[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Lava
Lava is the most important part of the volcano. The lava will be ejected from the volcano's main vent. The lava will flow down the volcano and will create the volcano's shape.

## Pillow Lava
Pillow lava is a type of lava that forms underwater. When the lava is ejected from the volcano's main vent and the lava flows into the water, the lava will form pillow lava.  

The pillow lava is implemented as a `MAGMA_BLOCK` block that is placed in the water. The pillow lava will have a chance to form when the lava flows into the water, or when the lava is ejected from underwater vents.

![Pillow Lava](/.github/docs/volcano/assets/pillow_lava.png)

## Silica Content
Just like real-life volcanoes, the lava's silica content will affect the palette of blocks that will be ejected from the volcano.  

### Types of Lava
Typhon plugin internally uses the following types of lava: (_italic_ means the block is for `Volcanic Bomb`)
- `ultramafic`: Very Low silica content lava. (`<41%`)
  - Cools to: `DEEPSLATE`,_`COBBLED_DEEPSLATE`_,_`NETHERRACK`_
- `mafic`: Low silica content lava. (`41% < x < 45%`)
  - Cools to: `BASALT`,`POLISHED_BASALT`,`DEEPSLATE`,_`COBBLED_DEEPSLATE`_,_`NETHERRACK`_
- `basaltic`: Low silica content lava. (`45% < x < 52%`)
  - Cools to: `BASALT`,`POLISHED_BASALT`,_`COBBLED_DEEPSLATE`_,_`NETHERRACK`_
- `basaltic-andesitic`: Medium silica content lava. (`52% < x < 57%`)
  - Cools to: `ANDESITE`,`TUFF`,`BASALT`,`POLISHED_BASALT`,_`COBBLED_DEEPSLATE`_,_`NETHERRACK`_
- `andesitic`: High silica content lava. (`57% < x < 63%`)
  - Cools to: `ANDESITE`, `TUFF`, _`TUFF`_
- `andesitic-dacitic`: High silica content lava. (`63% < x < 68%`)
  - Cools to: `ANDESITE`, `TUFF`, `QUARTZ_BLOCK`, `OBSIDIAN`, `CRYING_OBSIDIAN`, `GRANITE`, `COBBLESTONE`, `STONE`, _`TUFF`_
- `dacitic`: Very High silica content lava. (`68% < x < 72%`)
  - Cools to: `STONE`, `OBSIDIAN`, `QUARTZ_BLOCK`, `AMETHYST_BLOCK`, `GRANITE`
- `rhyolitic`: Extremely High silica content lava. (`72% < x`)
  - Cools to: `STONE`, `OBSIDIAN`, `QUARTZ_BLOCK`, `AMETHYST_BLOCK`, `GRANITE`

> [!TIP]  
> If you want to know more about the configuration, Chec the [VolcanoComposition.java](/src/main/java/me/alex4386/plugin/typhon/volcano/VolcanoComposition.java) source code for more information.

> [!NOTE]  
> <a name="ore_formation"></a>
> For realistic lava, The erupting lava has small chance to create `ore` variant of erupting block. For example, `netherrack` has a chance to create `nether_gold_ore` when the lava is erupted.  
> This is to mimic the real-life behavior of the lava that can create ore deposits when the lava is cooled.
> 
> In order to prevent abuse by just roaming around and collecting ores, the [`Primary Succession` routine](./succession.md) will remove surface ores after a certain period of time, For more information about Succession, please refer to the [Succession](./succession.md) documentation.

### Viscosity of the Lava
Higher silica content will result in more viscous lava, which will result in more explosive eruptions.  

> [!NOTE]  
> Due to Minecraft's static implementation of fluid logic that can not implement "extended range", Typhon Plugin will "generate" new lava source blocks when the lava stops flowing. This is to mimic the real-life behavior of runny lava such as `basaltic` lava.

### Configuring Silica Content

> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md) documentation.

For changing the silica content:  
`/volcano <volcano_name> mainvent config lavaflow:silicaLevel <silica_content>`  

The `<silica_content>` is a floating point number between `0` and `1`, representing a percentage of silica content. `0.53 = 53%`

## Gas Content
Just like real-life volcanoes, the lava will contain volcanic gases. The volcanic gases will be ejected from the volcano's main vent.

For changing the gas content:  
`/volcano <volcano_name> mainvent config level:gasContent <gas_content>`

The `<gas_content>` is a floating point number between `0` and `1`, representing a percentage of gas content. `0.53 = 53%`

## Commands
### Quickcool
Quickcool is a command that will cool the lava instantly. This command is useful when you want to stop the lava flow immediately.  

> [!NOTE]
> This command will cause errors with the lava flow. Such as:
> - The lava will not flow down the volcano.
> - The lava will not form pillow lava when the lava flows into the water.
> - The lava will not form ore deposits when the lava is cooled.
> - The lava will not generate new lava source blocks when the lava stops flowing on `"runny lava"`.
>   
> Interrupting the lava flow will cause volcano's shape to be incomplete and unrealistic.

For quickcooling the lava on all vents of the volcano:  
`/volcano <volcano_name> quickcool`

For quickcooling the lava on the mainvent:  
`/volcano <volcano_name> mainvent quickcool`


