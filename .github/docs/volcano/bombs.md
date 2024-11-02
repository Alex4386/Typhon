[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Bombs
Volcanic Bombs are the solidified lava that is ejected to the sky from the volcano during the eruption such as `"strombolian"`, `"vulcanian"`, `"plinian"`, `"surtseyan"` etc.

In Typhon Plugin, you can trigger the volcanic bombs to be ejected from the volcano is implemented via `FallingBlock` entities.

When the volcanic bombs are ejected, they will be thrown to the sky and fall back to the ground. The size of the volcanic bombs can be different how intense the eruption is.  

## Size of the Volcanic Bombs
The size of the volcanic bombs can be different based on the eruption style.  
Usually, the size of the volcanic bombs is calculated based on the `explosionPower` and `radius` of the bombs., by default, the size of the volcanic bombs is between `1` to `3` blocks.  

During the `plinian` eruption, the size of the volcanic bombs can be between `3` to `5` blocks., or can be even larger.  

## Falling to the ground
When the volcanic bombs fall back to the ground, they will explode and create a crater on the ground. The size of the crater can be different based on the `explosionPower` and `radius` of the bombs.  

The volcanic bombs can break the wooden blocks, glass blocks, and other fragile blocks. until it reaches the ground.  

When it crashes to the ground, The lava from the volcanic bombs can ooze out from the bomb and cool down to create a new block.




