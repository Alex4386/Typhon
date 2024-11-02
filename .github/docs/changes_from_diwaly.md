[< Return to Typhon Docs](/DOCS.md)  

# Changes from Diwaly's Volcano Plugin
This document will list the changes from Diwaly's Volcano Plugin to Typhon Plugin.

## Changes
* **Entire Rewrite**
  - Typhon Plugin is a complete rewrite of Diwaly's Volcano Plugin. The plugin will use `JSON` files to store volcano, eruption vents, and other data.
* **Realistic Eruption**
  - **Realistic Ejecta**: Instead of Diwaly's Volcano Plugin that utilizes predefined palette of blocks to compose the volcano by defining probability of each block, Typhon uses predefined materials based on user's silica content configuration
  - **Realistic Eruption**: Unlike Diwaly's Volcano Plugin that only flows lava, (i.e. "hawaiian" eruption), Typhon Plugin has variety of eruption styles such as "strombolian", "vulcanian" eruption. or "surtseyan", "plinian" eruption on specific conditions are met.
  - **Realistic Eruption Column**: When "vulcanian" eruption or "plinian" eruption is triggered, Typhon Plugin triggers Pyroclastic flows and ash clouds.
  - **Realistic Environment**: Typhon Plugin will try to mimic real-life volcano behavior as much as possible. such as, Volcanic gases.
  - **Pillow Lava**: Unlike Diwaly's Volcano Plugin that stops and generate stone when water is met, Typhon Plugin implements custom flow of lava to create pillow lava when the lava flows into the water.

## Commands
Unlike Diwaly's Volcano Plugin, Typhon Plugin uses different command scheme due to the rewrite.

* **Diwaly's Volcano Plugin**: `/volcano <subcommand> <name> <args>`
* **Typhon Plugin**: `/volcano <name> <subcommand...> <args>`

## Configuration
Typhon Plugin uses `JSON` files to store data. The configuration file will be stored in `plugins/typhon/` directory.

### Volcano Configuration
Unlike Diwaly's Volcano plugin, Typhon Plugin uses `JSON` files to store volcano data. The configuration file will be stored in `plugins/typhon/volcanoes/{volcano_name}` directory.

