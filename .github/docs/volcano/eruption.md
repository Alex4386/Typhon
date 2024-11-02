[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Eruption
You can erupt the volcanoes you created with Typhon Plugin.

## Start/Stop Eruption
> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

You can start and stop the eruption of the volcano by running the following command:  
`/volcano <volcano_name> mainvent <start|stop>`

## Eruption Style
Typhon Plugin currently implements the following with predefined typhon eruption style presets:  

Basically here are the volcanoes you can create:
| Hawaiian | Strombolian | Vulcanian |
|:--------:|:-----------:|:---------:|
| ![Hawaiian](/.github/docs/volcano/assets/hawaiian.png) | ![Strombolian](/.github/docs/volcano/assets/strombolian.png) | ![Vulcanian](/.github/docs/volcano/assets/vulcanian.png) |

For changing eruption style: 
`/volcano <volcano_name> mainvent style <style>`  
`<style>` can be one of the following: `hawaiian`, `strombolian`, `vulcanian`

> [!TIP]  
> By default, when you configure the eruption style, the plugin will automatically set [silica content](./lava.md#silica-content) and [config_nodes](./config_nodes.md) based on the eruption style you selected.  
> This is equivalent of running `/volcano <volcano_name> mainvent config erupt:autoconfig confirm` command. See [config_nodes](./config_nodes.md) for more information.

### Extra behaviors
Some eruption styles have extra behaviors:
- **hawaiian**: Hawaiian eruption will trigger `rootless cone` when the shield cone itself has grown to a certain height.
- **strombolian**: Strombolian eruption will shoot up [volcanic bombs](bombs.md).
- **vulcanian**: Vulcanian eruption will shoot up [volcanic bombs](bombs.md) and trigger [pyroclastic flows and ash clouds](ash.md).

> [!TIP]  
> Some of the behaviors not described here might be due to lava's silica content. You can check the [silica content](./lava.md#silica-content) for more information.

### Unconfigurable Eruption Style
The volcano will automatically switch to specific eruption styles when the conditions are met:
- `surtseyan`: Surtseyan eruption is a volcanic eruption that shoots up [volcanic bombs](bombs.md) that usually occurs underwater, or when the volcano's eruption is affected by the water.  
  This will be triggered when any of the following is met:
  - The volcano's summit is under the sea level.
  - The crater is filled with water.
- `plinian`: Plinian eruption is a powerful eruption that shoots up [volcanic bombs](bombs.md) and trigger [pyroclastic flows and ash clouds](ash.md).  
  This will be triggered when any of the following is met:
  - The volcano is currently undergoing [caldera formation](./caldera.md).
  - The volcano is currently undergoing [lateral blast](./lateral_blast.md).

## `baseY` of the Vent
The `baseY` variable is a Y coordinate of the base of the vent.  
This is used to calculate the height of the vent when the volcano is erupting on top of the existing cone.  

> [!NOTE]  
> If you have changed the eruption style to `strombolian` or `vulcanian`, the `baseY` will be automatically configured based on current height of the cone.  

For Manually setting the `baseY`:  
`/volcano <volcano_name> mainvent config bombs:resetBaseY`  
