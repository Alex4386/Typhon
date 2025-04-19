``![image](https://github.com/user-attachments/assets/8d9acc34-f98f-4dea-8667-4ccd25bd901e)

## What's new?
### Updating to latest versions
- Typhon now targets for Minecraft `1.21.5`
  - SoundSeeds aren't updated yet for `1.21.5` so It will be using `1.21.1` version of SoundSeeds
  - Added support for geothermally affected blocks and newly utilize `dry` variant of `grass` when affected by `geothermal` heat.
  - Added support for `1.21.4` tree variants to be affected by `geothermal` heat.
  - Added support for `1.21.4`'s `creaking_heart` also affected by `geothermal` heat.
- Typhon now targets BluemapAPI `2.7.4`

### New Integrations
- Typhon now supports `WorldGuard` for region protection and management. _(Experimental)_
- Typhon now supports `CoreProtect` for region protection and management. _(Experimental)_

> [!NOTE]  
> **The integration with `WorldGuard` and `CoreProtect` is still in beta and may not work as expected.**  
> Please note that the "bugs" occur and your home region can be devastated by the volcano.  

> [!TIP]  
> The `WorldGuard` integration doesn't prevent the volcano from erupting, but this will only prevent blocks from the volcanic eruption from being placed in the protected region.  
> The `CoreProtect` integration will only make sure that the blocks are properly logged.  


### Fixes
- Fixed BlueMap integration was failing in:
  - New subsidary vent formation
  - Deleting a volcano
- Fixed `TyphonNavigation` for better navigations (This will fix the issue with summit navigation)
- Added missing Tab completion for `/vol <name> mainvent caldera` command

### New Features
#### Plugin Architecture
- Added `TyphonQueuedHashMap` for better performance on Typhon's internal caches
- The response of volcano commands are more streamlined
- `TyphonNavigation` no longer just posts `wall-of-text`.

#### Lava Dome Eruption
- Implemented Lava Dome Eruption Style
- The lavadome will ooze

#### Volcanic Bombs
- Implemented blackbody radiation (via glowing FallingBlock) for each Volcanic bomb
  Now the bombs will glow in the dark with proper color based on the temperature of the bomb
  - Fixed the issue with underlying scoreboard teams for blackbody radiation isn't properly deleted on reload or shutdown
- Fixed the issue with the Volcanic bombs aren't properly spewing out the lava when landed nearby the vent.

#### Lava flows
- The cooled lava will now reflect the flowing direction of the lava flow.

#### Navigation
- Newly revamped navigation interface:

#### Geothermal
- The geothermal system has been revamped
  - Now the volcano will follow the logrithmic curve for geothermal heat
  - The geothermal rate of the "DORMANT" state of the volcano will have more geothermal heat than previous versions.
    This is in order to make the par with real-life volcanoes where the geothermal heat signatures are still present even when the volcano is dormant.
  - Other geothermal states also have more geothermal heat than previous versions
- The Volcanic Gas will now have proper checks for fire-resistance armors.
- The Volcanic Gas will now deal proper damage to undeads.
- When the volcano is in elevated state, Now the volcanic gas can trigger `"NAUSEA"` effect on players and entities.
- Improved tree detection logic for "killing trees" via geothermal heat and gas release
- Pyroclastic flows will "timber" the trees when it meets on trees. The trees will fall down and burn out, replaced with coal block and covered with ash.
- Fixed timber'd Tree-falls' aren't having correct directions.

#### Pyroclastic Flows
- Fixed pyroclastic flows were not properly directing and doing drifts, causing doing U-turns and other weird behaviors.  
  -> Now the pyroclastic flows will properly follow the direction of the flow direction.
- Revamped pyroclastic flow's ash accumulation system
  - Now the pyroclastic flow will accumulate ash on the ground even more than previous versions.
  - The ash accumulation will create gentle slopes on the ground just like real-life pyroclastic flows.
  - The ash accumulation amount for the pyroclastic flow has been increased drastically (from 1 to 3 blocks) for realistic stratovolcano formations.  
- Pyroclastic flows will now have range checks for the ash accumulation.
  - By default, the pyroclastic flow will flow nearby the vent and will not flow far away from the vent., but will still flow about the size of the volcanic cone of the pyroclasts.
  - But if the random roll and it meets the condition for Full Pyroclastic Flow, it will flow far away from the vent. This can be configured via `"ash:fullPyroclasticFlowProbability"` config node.
- Pyroclastic flows will smooth out the surface when it meets on obstacles.  
- Pyroclastic flows will "timber" the trees when it meets on trees. The trees will fall down and burn out, replaced with coal block and covered with ash.

#### Succession
- Default Volcanic Succession rate has been drastically decreased for more realistic primary succession.
- The Succession rate and probability of each succession cycle can be configured via command via `"succession:probability"` and `"succession:treeProbability"` config nodes.
- The succession can be disabled per vent via `"succession:enabled"` in the vent config node.
