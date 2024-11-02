[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Caldera
Caldera is a large volcanic crater, typically one formed by a major eruption leading to the collapse of the mouth of the volcano.  

Typhon Plugin allows you to create a caldera in the volcano, and even you can create a caldera lake if you want.  

## Start creating caldera
> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

To start creating a caldera, you need to run the following commands:
1. If you have created caldera before, you need to reset the caldera formation by running:  
   `/volcano <name> mainvent caldera clear`
2. `/volcano <name> mainvent caldera <radius> <?deep> <?oceanY>`: setup the caldera formation
    - `<radius>`: The radius or starting size of the caldera., The radius of the `"mouth"` of the caldera.
    - `<deep>`: The depth of the caldera. This is an optional argument, if not defined, it will automatically set it up via calculating via radius
    - `<oceanY>`: The y-coordinate of the water level if the caldera is you want to make a caldera lake. If this is left blank, It is purely random if it will generate caldera lake or not. if it is lower than the lowest point of the caldera rim, it will not generate caldera lake.

3. `/volcano <name> mainvent caldera start`: start the caldera formation

## Start the caldera formation
When the caldera formation setup is completed, It will automatically generate a blueprint for creating caldera.  
You can start the caldera formation via running `/volcano <name> mainvent caldera start`.  
  
As soon as you run this command, the vent will start [`plinian` eruption](./eruption.md#unconfigurable-eruption-style) sending obnoxious size of volcanic bombs to cone/outside of the volcano.

## Check current caldera formation status
You can check current status of caldera formation by running `/volcano <name> mainvent caldera`.  

## Skip the current caldera eruption
You can run `/volcano <name> mainvent caldera skip` to finish caldera formation sequence (without volcanic bombs) immediately.
