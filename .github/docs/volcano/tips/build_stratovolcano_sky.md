[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Builder Docs](../builder.md)  

# Stratovolcano to the Sky
## Building the shield basin of the volcano
The world of Minecraft is limited to `320` blocks in height. So, let's suppose that you want to create a volcano that reaches that limit, by creating a volcano with a height of `256` (`y=320`) from the `y=64`.

By utilizing calculation from [Build a Stratovolcano](build_stratovolcano.md), the height of the basin should be `256 * 2/3 = 170`, so the y-level of the basin should be `170 + 64 = 234`.

### Using builder to build basin of the volcano
1. `/volcano <name> mainvent style crater`  
   Set the style of the eruption to `crater` for building the basin.
2. `/volcano <name> mainvent style hawaiian`
   Set the style of the eruption to `hawaiian` for building the basin.
3. `/volcano <name> mainvent builder y_threshold 234`  
   Set the y_threshold to `234` for the basin.
4. `/volcano <name> mainvent builder enable`
   Enable the builder.
5. `/volcano <name> mainvent start`
   Start the eruption.

The volcano will automatically stop erupting when the y-level of the volcano reaches the threshold., Now you can build the cone on top of the basin.

## Building the andesite top of the volcano
For the andesite top, the y_threshold should be `320`.

1. `/volcano <name> mainvent style vulcanian`  
   Set the style of the eruption to `vulcanian` for building the andesite top.
2. `/volcano <name> mainvent builder y_threshold 320`
    Set the y_threshold to `320` for the andesite top.
3. `/volcano <name> mainvent builder enable`
    Enable the builder.
4. `/volcano <name> mainvent start`
    Start the eruption.


## Disabling the builder
To disable the builder, you need to run the following command:
```
/volcano <name> mainvent builder disable
```

# Other Tips
- [Build a Stratovolcano](./build_stratovolcano.md)