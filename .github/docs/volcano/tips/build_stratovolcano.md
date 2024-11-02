[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Builder Docs](../builder.md)  

# Build a Stratovolcano
Stratovolcano is a type of volcano that is built by alternating layers of lava flows, ash, and other volcanic debris.

In Typhon Plugin, you can build a stratovolcano by using the builder feature., which allows automatically stop erupting when the y-level of the volcano reaches the threshold.  

## Building the shield basin of the volcano
Suppose you want to create a volcano with a height of `200` (`y=264`) from the `y=64`.  

Due to hawaiian eruption having fluid lava, the slope of the hawaiian eruption is likely to be `1/3`.

Usually the basin of the volcano have `1/2` of the total height., and considering the part of the basin will be hidden by the cone on the top, the height of the basin should be `2/3` of the total height.

Thus, the height of the basin should be `200 * 2/3 = 133`., so the y-level of the basin should be `133 + 64 = 197`.

1. `/volcano <name> mainvent style crater`  
   Set the style of the eruption to `crater` for building the basin.
2. `/volcano <name> mainvent style hawaiian`
   Set the style of the eruption to `hawaiian` for building the basin.
3. `/volcano <name> mainvent builder y_threshold 197`  
   Set the y_threshold to `197` for the basin.
4. `/volcano <name> mainvent builder enable`
   Enable the builder.
5. `/volcano <name> mainvent start`
   Start the eruption.

The volcano will automatically stop erupting when the y-level of the volcano reaches the threshold., Now you can build the cone on top of the basin.

## Building the andesite top of the volcano
For the andesite top, the y_threshold should be `64 + 200 = 264`.

1. `/volcano <name> mainvent style vulcanian`  
   Set the style of the eruption to `vulcanian` for building the andesite top.
2. `/volcano <name> mainvent builder y_threshold 264`
    Set the y_threshold to `264` for the andesite top.
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
- [Building a Stratovolcano that reaches the sky](./tips/build_stratovolcano_sky.md)