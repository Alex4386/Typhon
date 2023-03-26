[< Return to Typhon Docs](/DOCS.md)  

# Volcano
Typhon plugin tries to mimick real-life volcanoes as much as possible.

## Features
The Typhon plugin implements Volcano differently than Diwaly's volcano plugin.

The following is implemented at the moment:  
* Multiple vents (summit vent/flank/parasitic cones)
  - Automatic subsidary vent formation
* Explosive Eruption
  - Volcanic bombs
* Volcanic gas
  - fumaroles
  - SOx reactions between iron/wood tools
* Lava
  - Silicate composition
  - ore formations
* Caldera

## Multiple vents
Since typhon implements multiple vents, config for each vents are different. Therefore, Use the following depending on your vent you are trying to access:  

* Main vent: `/volcano <name> mainvent ...`  
* Sub vent: `/volcano <name> subvent <ventname> ...`

For documentational reasons,  
This docs will include a command usage starting `/volcano <name> mainvent ...`. but please note that you can use same command as `/volcano <name> subvent <ventname> ...` as well.

### Fissure openings / parasitic cone formation
Typhon implements fissure/parasitic cone formations via `VolcanoAutoStart` from `v0.6.0`.  
  
For more detailed configuration, please check `{volcanoDir}/autoStart.json` for more configurations.  

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

- **Hawaiian:** This is a relatively calm, effusive style of eruption, characterized by the steady flow of lava. Hawaiian eruptions are associated with shield volcanoes and typically produce flat, gently sloping lava flows.
- **Strombolian:** This eruption style is characterized by periodic explosive bursts of lava and ash. Strombolian eruptions are typically associated with basaltic lava and are characterized by the formation of cinder cones.
- **Vulcanian:** This eruption style is characterized by short, violent explosions of gas and ash. Vulcanian eruptions are associated with stratovolcanoes and can produce dense ash clouds that can pose a hazard to aviation.
- **Peléan:** This is a highly explosive eruption style, characterized by the rapid release of gas and ash. Pelean eruptions are associated with stratovolcanoes and are characterized by pyroclastic flows.

You can force the eruption style via running `/volcano <name> mainvent style <style>`.  

> This configuration will automatically configure your eruption style and other config nodes. via running autoConfig.
> if you don't want to do that, configure with config nodes instead.

### Unconfigurable styles
The following eruption styles are activated when the conditions are met:  

- **Plinian:** This is a highly explosive eruption style, characterized by the ejection of large amounts of gas, ash, and pumice. In typhon, it will generate obnoxiously big chunk of volcanic bombs, shooting from the specified caldera when the caldera formation is triggered.
- **Surtseyan:** This eruption style is characterized by the interaction of lava and water, producing steam-driven explosions. In Typhon, if the volcano eruption sequence detects if its eruption phase is occurring in shallow water, it automatically activates surtseyan eruption.

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

## More specific activity status
Typhon Plugin implements each vent's activity as 5 different levels:  
`EXTINCT`, `DORMANT`, `MINOR_ACTIVITY`, `MAJOR_ACTIVITY`, `ERUPTING`.  

each was derived from real-life volcanic alert levels:  
- **EXTINCT:** This level is used to describe a volcano that has not erupted in historical times and is unlikely to erupt in the future.
- **DORMANT:** This level is used to describe a volcano that is not currently erupting but has erupted in the past and could erupt again in the future.
- **MINOR_ACTIVITY:** This level is used to describe a volcano that is exhibiting signs of increased activity, such as heightened seismicity, elevated gas emissions, or minor ash emissions. rapid geothermal activities could be found on crater when the vent reaches this level.
- **MAJOR_ACTIVITY:** This level is used to describe a volcano that is exhibiting significant signs of activity, such as frequent or continuous ash emissions, lava flows, or moderate to strong seismicity. you can see geothermal activities rapidly increased contrast to minor_activity and no longer limited to inside of crater rim.  
- **ERUPTING:** This level is used to describe a volcano that is currently erupting or is showing imminent signs of an eruption, such as sustained ash emissions, lava flows, or significant ground deformation.

The activity changes depending on these statuses periodically and could be changed via command `/volcano <name> mainvent status <code>`

## Geothermal
Typhon implements geothermal activities such as fumaroles.  

### Geothermal cooking
Typhon implements the `"boiling" eggs` on volcano. It was implemented via dropping "food" items will cause it to change into roasted equivalent, when the volcano is actively erupting or having elevated status

### Volcanic Gas
The Typhon plugin implements volcanic gases leaking via fumaroles in crater and flanks.  

These volcanic gases can give you poison effect and damages your item's durability you are holding/wearing or dropped on a ground.

## Succession
Typhon plugin implements succession. so, when the volcano is kept rest for a long time, plants such as grass can grow.  

Just like real-life that erosion making this process faster, If it is raining, This will make the process faster. 

