[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcano Builder
Volcano Builder is a feature of Typhon Plugin that allows you to build a volcano in a more controlled way.

## Enable
> [!NOTE]  
> For simplicity of the documentation, the following commands will use commands for `mainvent`. For subvent, Please refer to the [Eruption Vents](./vents.md#commands) documentation.

To enable the builder, you need to run the following command:
```
/volcano <volcano_name> mainvent builder enable
```

To disable the builder, you need to run the following command:
```
/volcano <volcano_name> mainvent builder disable
```

## Modes
- `y_threshold`: The builder will stop erupting when the y-level of the volcano reaches the threshold.
  - Set: `/volcano <volcano_name> mainvent builder y_threshold <y_level>`

## Examples
- [Build a Stratovolcano](./tips/build_stratovolcano.md)
- [Building a Stratovolcano that reaches the sky](./tips/build_stratovolcano_sky.md)

