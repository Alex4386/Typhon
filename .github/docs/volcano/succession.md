[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Succession
In Typhon Plugin, the Primary succession and Secondary succession are supported to simulate the natural ecosystem recovery after the volcanic eruption.

The process is simulated by the following steps:
- **Erosion**: The volcano's cone will be eroded.
  - This process will be faster if the weather is `storm`.  
- **Primary Succession**: The first plants will colonize the eroded land.  
  - This will create a new soil and grass on the top layer.
- **Secondary Succession**: The plants will be replaced by the trees and the forest will be formed.

> [!NOTE]  
> The succession process triggers, but it depends on the ["heat" value of the volcano](./status.md) and [Geothermal Activities](./geothermal.md).  
> The succession process will be interrupted if the volcano is in `major activity` or `eruption imminent/erupting` status.

## Commands
### Enable/Disable Volcano Global Succession
You can enable/disable the succession process of the volcano by running the following command:
```
/volcano <volcano_name> succession <true|false>
```

### Enable/Disable Vent Succession

> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

You can enable/disable the succession process of the vent by running the following command:
```
/volcano <volcano_name> mainvent config succession:enable <true|false>
```

### Manual Succession
You can manually trigger the succession process by running the following command:
```
/typhon succession enable
```

Then, you'll be given a `WOODEN_SHOVEL`. Right-click the shovel on the ground to trigger the succession process.

After you're done, you can disable the succession tool by running the following command:
```
/typhon succession disable
```

