[< Return to Typhon Docs](/index.md)

# Volcano Builder
You can easily create a volcano with Typhon Plugin.

## Example: Creating stratovolcano
For creating a stratovolcano, It can be divided into 3 stages:

1. Building a main basin of the volcano by `hawaiian eruption`
2. Building an andesite top by `vulcanian eruption`

### Dividing the Y-levels for each cones
Suppose you want to create a volcano with a height of 200 from the y 64.  
Due to hawaiian eruption having fluid lava, the slope of the hawaiian eruption is likely to be `1/3`.  

Usually the basin of the volcano have `1/2` of the total height.  
Considering the part of the basin hidden by the volcano, the height of the basin should be `2/3` of the total height.  

Thus, the height of the basin should be `200 * 2/3 = 133`.

### Using builder to build basin of the volcano
for the basin, the y_threshold should be `133 + 64 = 197`.

Follow the following command.
```
typhon create tambora hawaiian
volcano tambora mainvent hawaiian style crater
volcano tambora mainvent builder y_threshold 197
volcano tambora mainvent builder enable
volcano tambora mainvent start
```

The volcano will automatically stop erupting when the volcano.

### Using builder to build andesite top
for the andesite top, the y_threshold should be `64 + 200 = 264`.

Follow the following command.
```
volcano tambora mainvent style vulcanian
volcano tambora mainvent builder y_threshold 264
volcano tambora mainvent builder enable
volcano tambora mainvent start
```

The volcano will automatically stop erupting when the volcano.

### Done
You have successfully created a stratovolcano with Typhon Plugin!
