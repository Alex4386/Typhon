[< Return to Typhon Docs](/DOCS.md)  

# Volcano
Typhon plugin tries to mimic real-life volcanoes as much as possible.

> [!WARNING]  
> If you are migrating from Diwaly's Volcano plugin,  
> Please note that Typhon plugin is not compatible with Diwaly's Volcano plugin.

## Features
The Typhon plugin implements Volcano differently than Diwaly's volcano plugin.

The following is implemented at the moment:  
* Multiple vents (summit vent/flank/parasitic cones)
  - Automatic subsidiary vent formation
* Explosive Eruption
  - Volcanic bombs
  - Pyroclastic flows
* Volcanic gas and geothermal activities
  - fumaroles
  - SOx reactions between iron/wood tools
* Lava
  - Silicate composition
  - ore formations
* Caldera
* Lateral blast (landslide)

## Multiple vents
Since typhon implements multiple vents, config for each vents are different. Therefore, Use the following depending on your vent you are trying to access:  

* Main vent: `/volcano <name> mainvent ...`  
* Sub vent: `/volcano <name> subvent <ventname> ...`

For documentational reasons,  
This docs will include a command usage starting `/volcano <name> mainvent ...`. but please note that you can use same command as `/volcano <name> subvent <ventname> ...` as well.

### Fissure openings / parasitic cone formation
Typhon implements fissure/parasitic cone formations via `VolcanoAutoStart` from `v0.6.0`.  
  
For more detailed configuration, please check `{volcanoDir}/autoStart.json` for more configurations.  

## Pyroclastic flows
Typhon plugin implements pyroclastic flows via `BlockDisplay`s.  
If the volcano is erupting in `VULCANIAN` or bigger, It will generate pyroclastic flows.  
Pyroclastic flows will burn you to death if you are inside of it and burn and carbonize the organic blocks it touches (e.g. trees).  

### Lateral blast
Typhon plugin can trigger lateral blast.  
for generating lateral blast, You can do the following:

1. Run `/volcano <name> mainvent landslide config` to configure the lateral blast.
2. Run `/volcano <name> mainvent landslide setAngle` to set the angle of the lateral blast.
3. Run `/volcano <name> mainvent landslide start` to trigger the lateral blast.

## Eruption Plumes
When the volcano is erupting, It will generate eruption plumes.  
Flying inside or locating inside the plume will immediately burn you to death.

## Config Nodes
There are many options that you can configure in Typhon plugin's vent system.  
Please refer to [config_nodes.md](config_nodes.md) for more details.  

In order to check the config node value, you can use:  
- **READ** : `/volcano <name> mainvent config <node>` to get current node value.
- **WRITE**: `/volcano <name> mainvent config <node> <value>` to write the value. 

## Vent Types
Typhon Plugin currently implements the following vent types:  
* fissure
* crater

Please not that fissure vent is developed primarily for use with `hawaiian eruption`. and others were not thoroughly tested. use with caution.  
You can configure this by running command `/volcano <name> mainvent style <vent_type>`.  

## Eruption Styles
Typhon Plugin currently implements the following with predefined typhon eruption style presets:  

Basically here are the volcanoes you can create:

| Hawaiian | Strombolian | Vulcanian |
|:--------:|:-----------:|:---------:|
| ![Hawaiian](/.github/docs/volcano/assets/hawaiian.png) | ![Strombolian](/.github/docs/volcano/assets/strombolian.png) | ![Vulcanian](/.github/docs/volcano/assets/vulcanian.png) |

- **Hawaiian:** Most adequate for generating shield volcanoes or base for the stratovolcanoes.
- **Strombolian:** Throws volcanic bombs and lava fountains. Usually used for building cinder cones.
- **Vulcanian:** Generates Pyroclastic flows and ash clouds, usually erupts andesitic lava by default `autoConfig`. Usually used for building top of the stratovolcanoes.
- **Peléan:** Generates even more pyroclastic flows and ash clouds, usually erupts rhyolitic lava by default `autoConfig`. Usually used for building ash filled basin of the stratovolcanoes.

You can force the eruption style via running `/volcano <name> mainvent style <style>`.  

> [!NOTE]  
> Configuration via specified command automatically configure your eruption style and other config nodes. 
> which is equivalent of triggering `autoConfig`.
> 
> if you don't want to do that, configure with config nodes instead.

> [!TIP]  
> For building stratovolcanoes,  
> It is recommended to use the multiple style of eruption to build up the stratovolcano cone.
> 
> Typhon Plugin includes several `"builder"` tools for help you to build the volcano.
> For more information: Check [builder.md](builder.md) for more information.

### Unconfigurable styles
The following eruption styles are activated when the conditions are met:  

- **Plinian:** This eruption style is automatically activated when the caldera formation is triggered. Usually used for building the ash basin of the stratovolcanoes.
- **Surtseyan:** This is a reserved eruption style when the vent is erupting from the ocean and summit is within the 10 blocks of the ocean surface. The eruption gets more explosive and generates more bombs.

## Cinder cones
Typhon plugin can generate `cinder cones` when the volcano is erupting `Strombolian` style.  
When the `strombolian` eruption is triggered, Typhon plugin will generate cinder cones with volcanic bombs for building itself.  

When the condition is met, Typhon automatically set up the internal `baseY` value to set the base of the cinder cone.  
If you are going to change the `baseY` value, you can use `/volcano <name> mainvent config bombs:resetBaseY` to reset the baseY value to current `SummitBlock`'s Y value.  
(This is automatically triggered when you are switching from other eruption styles.)

## Lava flows
### Lava compositions
unlike diwaly's volcano plugin which allows users to pick the specific blocks to compose volcano, typhon doesn't allow this.  
Instead, Typhon use silicate level of lava to decide which rock blocks to be chosen.  

* Basaltic:  
  - Flow: Basalt (Basalt, Deepslate)
  - Bomb: Scoria (Netherrack, blackstone, cobbled deepslate)
* Andesitic:
  - Flow: Andesite (Andesite, Stone)
  - Bomb: Andesite (Tuff)
* Rhyolitic:
  - Flow: Rhyolite (Granite)
  - Bomb: Tuff, Quartz (Tuff, Nether quartz, amethyst)

For more information: check [VolcanoComposition.java](/src/main/java/me/alex4386/plugin/typhon/volcano/VolcanoComposition.java).  
You can configure this manually by setting config node `lavaflow:silicateLevel`.  

### Pillow Lava
Typhon plugin simulates lava flowing underwater just like real-life by using magma block as a alternative of lava block, allowing volcanic cone build up from underwater possible.  

### Lava extension
Typhon plugin simulates viscosity of lava by implementing `lava extension` when lava reaches its terminal.  
When the silicate content is low so that real-life counterpart can flow more, Typhon plugin closes gap of these differences via implementing an extension hook on lava cooldown, allowing lava to flow more like in real-life alternatives.

This feature depends on `lavaflow:silicateLevel` config node and activates when the lava is basaltic.  

### Ore generation
Just like real-life volcano, typhon plugin generates ore when lava is cooled down.
In order to make it more realistic, the more you go near to the volcanic plug, It is more rich in ores. But just like in real-life, the hazards of the volcanoes are implemented just like same. See the other sections for more information.    

### Rootless cones
When the vent is erupting `HAWAIIAN` style, Typhon plugin can generate rootless cones.  
Rootless cones are formed when lava flows over water-saturated ground, causing the water to flash to steam and create an explosion., in the range of the flowing lava from the vent.

### Explosive Eruptions
Typhon plugin implements Volcanic bombs via `FallingBlock`s.  
  
When the volcano is erupting, It will build up the cinder cone with volcanic bomb with bombs and debris falling from sky as falling block.  

> Due to performance restrictions,  
> Some of the volcanic bombs are not rendered as `FallingBlock` but can land and explode.  

### Ash falls
Although not completely implemented at the point (`ver.0.7.0-rc2`), If there is eruption big enough (such as vulcanian or bigger), Typhon plugin randomly stacks tuff over a biome, allowing users to experience more realistic volcano.  

### Caldera formation
Just like real-life, Typhon plugin can create caldera at the volcano.  
Run `/volcano <name> mainvent caldera` to create caldera.  
  
This will be automatically triggered by chance if the volcano reaches the build limit.  
For more information, check [caldera.md](caldera.md) for more information of creating calderas.  

## Activity status
Typhon Plugin implements each vent's activity as 5 different levels:  
`EXTINCT`, `DORMANT`, `MINOR_ACTIVITY`, `MAJOR_ACTIVITY`, `ERUPTING`.  

The following icons are available from BlueMap:


| Extinct | Dormant |                              Minor Activity                               |                    Major Activity / Eruption Imminent                     | Erupting |
|:-------:|:-------:|:-------------------------------------------------------------------------:|:-------------------------------------------------------------------------:|:--------:|
| ![Extinct](/src/main/resources/icons/[footage]/extinct.svg) | ![Dormant](/src/main/resources/icons/[footage]/dormant.svg) | ![Minor Activity](/src/main/resources/icons/[footage]/minor-activity.svg) | ![Major Activity](/src/main/resources/icons/[footage]/major-activity.svg) | ![Erupting](/src/main/resources/icons/[footage]/erupting.svg) |

Each was derived from real-life volcanic alert levels:  
- **EXTINCT:** VolcanoAutoStart will not trigger the eruption, The volcano has no `heat` value for geothermal activities.
- **DORMANT:** The volcano shows minor heat signatures time-to-time, but it doesn't happen all the time.
- **MINOR_ACTIVITY:** The volcano shows stable releases of heat signatures such as fumarole near the summit.
- **MAJOR_ACTIVITY:** The volcano is showing blatantly higher geothermal activities and heat signatures near the summit. stable heat signatures from the volcano flanks are also visible.
- **ERUPTION_IMMINENT:** This level is used in cases to force volcano to have geothermal and other volcanic activities as if `ERUPTING` but without active eruption session on the vent. 
- **ERUPTING:** Volcano is actively erupting and lava, bomb or ashes are spewing out from the vent.

The activity changes depending on these statuses periodically and could be changed via command `/volcano <name> mainvent status <code>`

### Geothermal
Typhon implements geothermal activities such as fumaroles based on the activity status.  

#### Geothermal cooking
Typhon implements the `"boiling" eggs` on volcano. It was implemented via dropping "food" items will cause it to change into roasted equivalent, when the volcano is actively erupting or having elevated status

### Volcanic Gas
The Typhon plugin implements volcanic gases leaking via fumaroles in crater and flanks.
These volcanic gases can give you poison effect and damages your item's durability you are holding/wearing or dropped on a ground.

## Succession
Typhon plugin implements succession. so, when the volcano is kept rest for a long time, plants such as grass can grow.
Just like real-life that erosion making this process faster, If it is raining, This will make the process faster. 

### Succession Tool
YOu can manually trigger succession with `WOODEN_SHOVEL`.  
Enable the tool by running `/typhon successor enable` and right-click the `WOODEN_SHOVEL` on the ground to trigger succession.

You can disable the tool by running `/typhon successor disable`.


