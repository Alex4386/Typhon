[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Eruption Vents
The volcano in Typhon Plugin can have multiple vents. Each vent can have different eruption style and vent type.

## Vent Type
Typhon Plugin currently supports the following vent types:
- `crater`: Most common vent type. It is the vent type that is located at the center of the volcano.
- `fissure`: A vent type that simulates as a crack on the ground in fissure eruption. the lava will flow from the virtual line of the fissure.

> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

For changing vent type:  
`/volcano <volcano_name> mainvent style <vent_type>`  
`<vent_type>` can be one of the following: `crater`, `fissure`

### Fissure
The fissure is the vent type that simulates as a crack on the ground in fissure eruption. the lava will flow from the virtual line of the fissure.

**Configuring Fissure:**
- `fissureAngle`: Sets the angle of the volcano's fissure. (in radians)
  In order to set the angle by looking at the player's direction, you can use the following command:
  ```
  /volcano <volcano_name> mainvent config vent:fissureAngle auto
  ```
- `fissureLength`: Sets the length of the volcano's fissure.

### Crater
The crater is a circular vent type that is located at the center of the volcano.  
The lava will spew out from the center of the crater, and the lava will flow down the volcano., creating the volcano's shape.  

**Configuring Crater:**
- `craterRadius`: Sets the radius of the volcano's crater.

## The Multiple Vents
### The `Main Vent`
By default, when you create the volcano, the plugin will automatically create the main vent.  

The main vent is the vent that is located at the center of the volcano. The main vent is the vent that will be used to erupt the volcano.

### Creating Subvent
You can create subvent by running the following command:
- `/volcano <volcano_name> create crater <subvent_name>`
- `/volcano <volcano_name> create fissure <subvent_name>`

### Creating Subvent near someone
You can create subvent near someone by running the following command:
- `/volcano <volcano_name> create autovent <subvent_name> <player_name>`

### Monogenetic vent
By default the vent is set to `polygenetic` mode, which means the vent will erupt multiple times.  
If you want to make your vent to erupt only once, you can set the vent to `monogenetic` mode.

See [Get/Set Monogensis mode](#getset-monogensis-mode) for how to set the vent to monogenesis mode, and vice versa.

## Commands
> [!NOTE]  
> For simplicity of the documentation, the following commands will using commands for `mainvent` like following:  
> `/volcano <volcano_name> mainvent <subcommand> <...args>`  
>   
> If you want to use the command for `subvent`, change the command like following:
> `/volcano <volcano_name> subvent <subvent_name> <subcommand> <...args>`
>
> For example, if you want to change the eruption style of the subvent, you can use the following command:
> `/volcano <volcano_name> subvent <subvent_name> style <style>`

### Start/Stop Eruption
You can start and stop the eruption of the volcano by running the following command:
- `/volcano <volcano_name> mainvent <start|stop>`

### Switching Vent
> [!NOTE]  
> This command is only available for the subvents.

You can change subvent into the mainvent by running the following command:
- `/volcano <volcano_name> subvent <subvent_name> switch`

The main vent will be changed to subvent with the same name, and the subvent will be changed to the main vent.

### Configuring Vent
You can configure the main vent by running the following command:  
- `/volcano <volcano_name> mainvent config <args>`

For more information about configuring the vent, please refer to the [Config Nodes](./config_nodes.md) documentation.

### Finding Summit
You can find the summit of the vent by running the following command:
- `/volcano <volcano_name> mainvent summit`

### Get/Set Monogensis mode
You can get and set the vent to monogenesis mode by running the following command:

- Get the monogenesis mode:  
  `/volcano <volcano_name> mainvent genesis`
- Set the monogenesis mode:  
  `/volcano <volcano_name> mainvent genesis <mode>`
  `<mode>` can be one of the following: `monogenetic`, `polygenetic`

### Delete Vent
> [!NOTE]  
> This command is only available for the subvents.

You can delete the vent by running the following command:
- `/volcano <volcano_name> subvent <subvent_name> delete`

### Builder
> [!TIP]
> For more information about the builder, please refer to the [Builder](./builder.md) documentation.

You can get information of the builder for the vent by running the following command:
- `/volcano <volcano_name> mainvent builder <...>`

