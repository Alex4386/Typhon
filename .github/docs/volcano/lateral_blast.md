[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Lateral Blast
Lateral Blast is a volcanic eruption that occurs when the volcano's cone is destroyed by the eruption.

In Typhon Plugin, You can trigger the lateral blast if the cone is grown to a certain height.

## Start creating lateral blast

> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

To start creating a lateral blast, you need to run the following commands:
1. If you have created lateral blast before, you need to reset the lateral blast formation by running:  
   `/volcano <name> mainvent landslide clear`
2. `/volcano <name> mainvent landslide setAngle <radians>`: setup the blast direction
    - `<radians>`: The angle of the lateral blast. This is in radians.
    - If you want to set the angle by looking at the player's direction, you can use the following command: `/volcano <name> mainvent landslide setAngle auto`
3. `/volcano <name> mainvent landslide start`: start the lateral blast formation

## Start the lateral blast formation
You can start the lateral blast formation via running `/volcano <name> mainvent landslide start`.

As soon as you run this command, the vent will start [`plinian` eruption](./eruption.md#unconfigurable-eruption-style) sending obnoxious size of volcanic bombs to set direction of the blast, just like Mt. St. Helens collapse.  
The blast will destroy the cone and create a large horse-shoe shaped crater.





