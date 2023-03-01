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
* 

## Multiple vents
Since typhon implements multiple vents, config for each vents are different. Therefore, Use the following depending on your vent you are trying to access:  

* Main vent: `/volcano <name> mainvent ...`  
* Sub vent: `/volcano <name> subvent <ventname> ...`

### Fissure openings / parasitic cone formation
Typhon implements fissure/parasitic cone formations via `VolcanoAutoStart` from `v0.6.0`.  
  
For more detailed configuration, please check `{volcanoDir}/autoStart.json` for more configurations.  

## Vent Types
Typhon Plugin currently implements the following vent types:  
* fissure
* crater

Please not that fissure vent is developed primarily for use with `hawaiian eruption`. and others were not thoroughly tested. use with caution.  

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

### Pillow Lava
Typhon plugin simulates lava flowing underwater just like real-life by using magma block as a alternative of lava block.  

### Lava extension
Typhon plugin simulates runniness of lava by implementing `lava extension` when lava reaches its terminal.  
When the silicate content is low so that real-life counterpart can flow more, Typhon plugin closes gap of these differences via implementing an extension hook on lava cooldown, allowing lava to flow more like in real-life alternatives.

### Ore generation
Just like real-life volcano, typhon plugin generates ore when lava is cooled down.  

In order to make it more realistic, the more you go near to the volcanic plug, It is more rich in ores. But just like in real-life, the hazards of the volcanoes are implemented just like same. See the other sections for more information.    

## Eruption Styles
Typhon Plugin currently implements the following with predefined typhon eruption style presets:  

* hawaiian eruption
* strombolian eruption
* vulcanian eruption
* peléan eruption
* plinian eruption (caldera formation)
* surtseyan eruption (automatic)

You can force the eruption (unless it is plinian/surtseyan) via running `/volcano <name> mainvent style <style>`.  

### Explosive Eruptions
Typhon plugin implements Volcanic bombs via `FallingBlock`s.  
  
When the volcano is erupting, It will build up the cinder cone with volcanic bomb with bombs and debris falling from sky as falling block.  

| Due to performance restrictions,  
| Some of the volcanic bombs are not rendered as `FallingBlock` but can land and explode.  

### Ash falls
Although not completely implemented at the point (`ver.0.7.0-rc2`), If there is eruption big enough (such as vulcanian or bigger), Typhon plugin randomly stacks tuff over a biome, allowing users to experience more realistic volcano.  

### Caldera formation
Just like real-life, Typhon plugin can create caldera at the volcano.  

Run `/volcano <name> mainvent caldera` to create caldera.  
  
This will be automatically triggered by chance if the volcano reaches the build limit.  

## More specific activity status
Typhon Plugin implements each vent's activity as 5 different levels:  
`EXTINCT`, `DORMANT`, `MINOR_ACTIVITY`, `MAJOR_ACTIVITY`, `ERUPTING`.  

The activity (leakage of volcanic gas and geothermal activities) changes depending on these statuses and could be changed via command `/volcano <name> mainvent status <code>`

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

