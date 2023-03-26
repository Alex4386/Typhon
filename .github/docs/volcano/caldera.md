[< Return to Volcano Page](index.md)  
# Caldera
<p align="right">Last Update: <b>v0.7.0-rc2</b></p>

## Start creating caldera
Creation of caldera consists 2 stages
1. `/volcano <name> mainvent caldera <radius> <?deep> <?oceanY>`: setup the caldera formation
2. `/volcano <name> mainvent caldera start`: start the caldera formation

## Setup the caldera formation
The `/volcano <name> mainvent caldera <radius> <?deep> <?oceanY>` command is used to create a caldera. 

The command takes the following arguments:

* `<radius>`: The radius or starting size of the caldera.
* `<deep>`: The depth of the caldera. This is an optional argument, if not defined, it will automatically set it up via calculating via radius
* `<oceanY>`: The y-coordinate of the water level if the caldera is you want to make a caldera lake. If this is left blank, It is purely random if it will generate caldera lake or not. this should not over the lowest point of the caldera rim.

Example: `/volcano Tambora mainvent caldera 140 50 83`.  

## Start the caldera formation
When the caldera formation setup is completed, It will automatically generate a blueprint for creating caldera.  
You can start the caldera formation via running `/volcano <name> mainvent caldera start`.  
  
As soon as you run this command, the vent will start `plinian` eruption. sending obnoxious size of volcanic bombs to cone/outside of the volcano.

## Check current caldera formation status
You can check current status of caldera formation by running `/volcano <name> mainvent caldera`.  

## Skip the current caldera eruption
You can run `/volcano <name> mainvent caldera skip` to finish caldera formation sequence (without volcanic bombs) immediately.
