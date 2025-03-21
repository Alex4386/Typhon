[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Geothermal Activities
The volcano in Typhon Plugin can have geothermal activities depending on [the status of the vent](./status.md).  

## Geothermal Heat
The geothermal heat generated by the volcano will affect the surrounding area.  
The geothermal heat will be generated by the vent based on the status of the vent, and the heat will be spread to the surrounding area.  

Anywhere inside the cone of the volcano will have geothermal heat value, and the heat value will be decreased as the distance from the vent increases. (this includes subvents, too)

### Cooking
The geothermal heat can be used to cook the food.  
The food will be cooked when the food is placed near the geothermal heat. Use your drop key to drop the food in the range of the geothermal heat.  

- **Dormant:** The food has little chance to be cooked inside the crater.
- **Minor Activity:** 
  - **Inside the crater:** The food has little chance to be cooked inside the crater.
- **Major Activity:**
  - **Inside the crater:** The food will be cooked inside the crater.
  - **On the cone/flanks:** The food has little chance to be cooked on the cone/flanks.
- **Eruption Imminent/Erupting:** The food will be cooked anywhere on the volcano.

## Fumarole
Fumarole is a vent in the Earth's surface that emits steam and gases. 
Depending on the status of the vent.  

The fumarole can be easily found on inside the crater of the volcano., but it can also be found on the cone or flanks of the volcano.  

### Emission Rate
Depending on the status of the vent, the amount of fumarole spawned will be different.

- **Extinct:** No fumarole will be spawned.
- **Dormant:** The fumarole will spawn occasionally only inside of the crater.
- **Minor Activity:** Sustained amount fumarole will spawn inside the crater. The fumarole will spawn occasionally on the cone/flanks.
- **Major Activity:** More fumarole will spawn inside the crater. Sustained number of fumaroles are available on the cone/flanks.
- **Eruption Imminent/Erupting:** The fumarole will spawn anywhere on the volcano. Entire crater will be filled with fumaroles' steam.

### Effects
- **Dormant:** The fumarole will emit steam only., no damage will be dealt.
- **Minor Activity:** 
  - **Inside the crater:** The fumarole will emit steam and gases. The fumarole will have a chance to emit [volcanic gases](#volcanic-gases).
  - **On the cone/flanks:** The fumarole will emit steam only.
- **Major Activity:**
  - The fumarole will emit steam and [volcanic gases](#volcanic-gases).
  - The concentration of the gases will be higher than the minor activity that can kill the trees or plants

## Volcanic Gases
In order to match with the real-life volcanic behavior, Typhon Plugin implements volcanic gases.  
Just like real-life volcanic gases, the volcanic gases can be harmful to the player.  

Following can be affected due to volcanic gases:
- **Trees:**
  - The trees will "die" when the trees are exposed to the volcanic gases multiple times.
  - The dead trees will have no leaves.  
- **Plants:**
  - The plants will "die" when the plants are exposed to the volcanic gases multiple times.
- **Entities:**
  - **Chemical Burn:** The entities will have a chance to get a "chemical burn" when the entities are exposed to the volcanic gases.
  - **Nausea:** The entities will have a chance to get "nausea" effect when the entities are exposed to the volcanic gases.
  - **Poison:** The entities will have a chance to get "poison" effect when the entities are exposed to the volcanic gases.
- **Tools:**
  - Wooden or Iron tools will have a chance to get "corroded" when the tools are exposed (holding or dropped) to the volcanic gases.
