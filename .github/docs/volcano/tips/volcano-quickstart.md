[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](../index.md)  

# Create and Erupt a Volcano
Creating a volcano in Typhon Plugin is easy and fun. You can create a volcano in a few steps and watch it erupt.

## Create a Volcano
1. Move to the location where you want to create a volcano.
2. Run the following command to create a volcano:
   ```
   /typhon create <volcano_name>
   ```
   - `<volcano_name>`: The name of the volcano.
3. Set the vent type
   ```
   /volcano <volcano_name> mainvent style <vent_type>
   ```
   - `<vent_type>`: The type of vent for the volcano. (`crater`, `fissure`)  
     Refer to [Eruption Vents](../vents.md#vent-types) for more information.
4. Set the eruption style
    ```
    /volcano <volcano_name> mainvent style <eruption_style>
    ```
    - `<eruption_style>`: The style of eruption for the volcano. (`hawaiian`, `strombolian`, `vulcanian`)  
      Refer to [Eruption Styles](../eruption.md#eruption-style) for more information.

## Erupt the Volcano
1. Start the eruption
   ```
   /volcano <volcano_name> mainvent start
   ```

## Stop the Eruption
1. Stop the eruption
   ```
   /volcano <volcano_name> mainvent stop
   ```
