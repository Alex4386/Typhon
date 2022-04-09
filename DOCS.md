# Docs
<p align="right">Last Updated: <b>2021-07-25</b> for v0.5.0</p>

## Welcome to Typhon Plugin!
Typhon plugin is a Minecraft Plugin aims to implement real-life volcanoes and its behavior in Spigot within the borders of vanilla minecraft (no mods/texturepacks)

> **Warning:**  
> Typhon Plugin is still in early development, and might introduce feature that might break other features.  
> Use at your own risk!

## Testing your gear against Typhon Plugin
Any minecraft servers supports Spigot API can work with Typhon Plugin. 

But, Typhon plugin was only properly tested on [Paper](https://papermc.io).  
Due to extreme I/O use and CPU-Intensive job that Typhon plugin does to your Minecraft server, I recommend using Paper for best experience.

### Benchmarking your gear with erupting a volcano in ludicrous speed
In order to test your gear is adequate for Typhon plugin, try the following:  

1. give yourself a `op` or adequate permissions to run Typhon plugins
2. move to a location where you want to create a test volcano. (land on the ground if you are flying)
3. type `/typhon create <name>` (replace `<name>` in your favor)
4. when you see a message that volcano was created. type `/vol <name>` to check available commands
5. Now let's configure the volcano for benchmarking your gear.
6. configure volcano's update rate to `5` by running `/vol <name> updaterate 5` 
   (`updaterate` is how manytick does it take to process a single stage. If you want to make volcano to erupt faster, set it as a low value. (but i don't recommend going below 5))
7. configure volcano's mainvent's eruption delay to `2` ticks by running `/vol <name> config erupt:delay 2`.
8. configure volcano's minimum erupting volcanic bombs to 100 by running `/vol <name> config erupt:bombs:min 100`.
9. configure volcano's maximum erupting volcanic bombs to 200 by running `/vol <name> config erupt:bombs:max 200`.
10. Now start the eruption by typing `/vol <name> mainvent start`.  

Now probably the volcano will be erupting right now:  
![image](https://user-images.githubusercontent.com/27724108/126882463-2a55ecec-9c2a-4a7d-8236-5c6603734f8e.png)

Please check if your `tps` is reaching dangerous levels.  
If then, you need to tweak the plugin to be lightweight.  

### Stopping the benchmark
If you want to stop the benchmark. you can run: `/vol <name> mainvent stop`.  
By default, It will try to shut down gracefully by processing lava flows after the shutdown request.  
If you want it to stop immediately, You can run: `/vol <name> mainvent quickcool`.  
(This will turn all lava blocks (related to current lavaflows tracked by plugin) into solid rocks)

## Configuring Volcano
TBD
