[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](../index.md)  

# Create a Volcano with Multiple Vents
You can have multiple vents in a volcano to create a more realistic eruption.

## Create a subvent
1. Create a volcano with a mainvent.
   Refer to [Create and Erupt a Volcano](./volcano-quickstart.md) for more information.  
2. Head to the location where you want to create a subvent.
3. Run the following command to create a subvent:
   ```
   /volcano <volcano_name> create <subvent_type> <subvent_name>
   ```
    - `<volcano_name>`: The name of the volcano.
    - `<subvent_type>`: The type of subvent for the volcano. (`crater`, `fissure`)  
      Refer to [Eruption Vents](../vents.md#vent-types) for more information.
    - `<subvent_name>`: The name of the subvent.
4. Configure the subvent type
    ```
    /volcano <volcano_name> subvent <subvent_name> style <vent_type>
    ```
      - `<vent_type>`: The type of vent for the subvent. (`crater`, `fissure`)  
        Refer to [Eruption Vents](../vents.md#vent-types) for more information.
5. Configure the subvent eruption style
    ```
    /volcano <volcano_name> subvent <subvent_name> style <eruption_style>
    ```
      - `<eruption_style>`: The style of eruption for the subvent. (`hawaiian`, `strombolian`, `vulcanian`)  
        Refer to [Eruption Styles](../eruption.md#eruption-style) for more information.

## Erupt the Volcano
1. Start the eruption
   ```
   /volcano <volcano_name> subvent <subvent_name> start
   ```

## Stop the Eruption
1. Stop the eruption
   ```
   /volcano <volcano_name> subvent <subvent_name> stop
   ```

## Make subvent a mainvent
1. Make the subvent a mainvent
   ```
   /volcano <volcano_name> subvent <subvent_name> switch
   ```
    The subvent will become the mainvent and the mainvent will become the subvent with the same name. Refer to [Eruption Vents](../vents.md#switching-vent) for more information.

