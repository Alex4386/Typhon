
# Using Typhon Plugin with Gaia
Gaia is a new volcano management system (added on `0.7.0-rc2`) that creates volcanoes, automatically depending on currently available chunks. `Gaia` automatically adds volcanoes when your world expands, providing randomly generated volcano when you need so.    

## Using Gaia on manual volcano creation
The volcano creation now automatically creates volcano name if you don't supply it.    
You can now use `/typhon create` in-game to create new volcano with automatic settings.  
  
## Allow Gaia to automatically generate volcanoes in your world
By default, (preventing mass destruction), `Gaia` can't generate volcanoes in any of your worlds.  
You can allow Gaia to generate volcanoes in specified world by running `/typhon gaia enable-world <world>`.  

### How does Gaia generate volcanoes?
Gaia searches through previously generated chunks (a.k.a. the chunks that have mca files), and generate one near by the volcano.  
To prevent world basically "filled" with volcano, Gaia implements a `"bubble"` system.  
It means, if the volcano is generated, a `"bubble"` (by default, 4000x4000 blocks) becomes a no-volcano zone. (This means, by default, 62500 chunks should be discovered in order to create 1 more volcano).  
  
You can configure this via running `/typhon gaia bubble <bubble>`.  

### How can I check for Gaia's status?
To check Gaia's current status and quota for the volcanoes, You can run `/typhon gaia worlds <world>`  

### How can I force gaia to generate new volcano?
You can force Gaia to generate a new volcano by running `/typhon gaia worlds <world> spawn`.  
If you don't want it to be random, use `/typhon create` mentioned above.  

