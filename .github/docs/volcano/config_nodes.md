[< Return to Volcano Page](index.md)

# Typhon Vent Config Nodes

<p align="right">Last Updated: <b>v0.7.0-rc2</b></p>

## Lavaflow

### lavaflow:delay
Sets the delay between lava flow iterations in ticks.    
A higher delay will cause the lava to flow slower, while a lower delay will cause the lava to flow faster.  

### lavaflow:flowed
Sets the amount of time that the lava has been flowing.

### lavaflow:silicateLevel
Sets the amount of silicate in the lava flow. This determines how quickly the lava will flow and how far it will travel.  
The value is float and considers 1 as 100%.  
  
This value will impact on following:  
* **Viscosity of Lava:**
  The viscosity of lava plays an important role in determining its behavior during an eruption. Viscosity is the measure of a fluid's resistance to flow. The viscosity of lava is determined by the amount of silicate minerals present in the lava. Lava with a high viscosity flows more slowly and tends to form lava domes or lava flows with steep sides.  
  On the other hand, lava with a low viscosity flows more quickly and tends to form flat lava flows that travel farther.
* **Composition of the Volcanic Rocks:**
  The amount of silicate minerals present in the lava has a significant impact on the composition of the resulting extrusive volcanic rocks.  
  Lava with a lower silicate content (such as basaltic lava) will result in the formation of extrusive volcanic rocks that are basaltic.  
  Lava with a higher silicate content (such as rhyolitic lava) will result in the formation of extrusive volcanic rocks that are lighter in color, have a coarser texture, and are more viscous.
* **Eruption Styles:**  
  Lava with a lower silicate content (such as basaltic lava) is less viscous and flows more easily, which can result in effusive eruptions (or explosive eruptions such as the Strombolian eruption).  
  Lava with a higher silicate content (such as rhyolitic lava) is more viscous and flows more slowly, which can result in explosive eruptions. 

### lavaflow:gasContent
Sets the amount of gas in the lava flow. This determines the likelihood of explosions occurring during the eruption, and decide likelyhood of explosiveness of the eruption.

## Bombs

### bombs:explosionPower:min
Sets the minimum explosion power of volcanic bombs.

### bombs:explosionPower:max
Sets the maximum explosion power of volcanic bombs.

### bombs:radius:min
Sets the minimum radius of volcanic bombs.

### bombs:radius:max
Sets the maximum radius of volcanic bombs.

### bombs:delay
Sets the delay between bomb explosions after landing in ticks.

## Erupt

### erupt:style
Sets the eruption style of the volcano.
- **Hawaiian:** This is a relatively calm, effusive style of eruption, characterized by the steady flow of lava. Hawaiian eruptions are associated with shield volcanoes and typically produce flat, gently sloping lava flows.
- **Strombolian:** This eruption style is characterized by periodic explosive bursts of lava and ash. Strombolian eruptions are typically associated with basaltic lava and are characterized by the formation of cinder cones.
- **Pelean:** This is a highly explosive eruption style, characterized by the rapid release of gas and ash. Pelean eruptions are associated with stratovolcanoes and are characterized by pyroclastic flows, which are fast-moving currents of hot gas and volcanic debris.
- **Vulcanian:** This eruption style is characterized by short, violent explosions of gas and ash. Vulcanian eruptions are associated with stratovolcanoes and can produce dense ash clouds that can pose a hazard to aviation.

### erupt:autoconfig
Automatically configures the volcano based on its current eruption style.  
use `/volcano <name> mainvent|subvent <subvent_name> config erupt:autoconfig confirm` to apply automatic configuration for the `erupt:style` you have configured.  

## Explosion

### explosion:bombs:min
Sets the minimum number of explosion power of bombs.

### explosion:bombs:max
Sets the maximum number of explosion power of bombs.

### explosion:scheduler:size
Sets the size of volcanic bomb launch queue

### explosion:scheduler:damagingSize
Sets the size of the damaging explosion size launched from summit via scheduler.

## Vent
### vent:craterRadius
Sets the radius of the volcano's crater.

### vent:fissureAngle
Sets the angle of the volcano's fissure. (in radians)

### vent:fissureLength
Sets the length of the volcano's fissure.

### vent:type
Sets the type of the volcano.

- **CRATER**: builds up volcano from central vent, spewing out lava from central location, creating conical shape.
- **FISSURE**: open up entire fissure from central vent, builds up lava flow range from central fissure


### vent:silicateLevel
Sets the amount of silicate in the volcano's lava.
