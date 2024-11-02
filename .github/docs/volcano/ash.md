[<  Return to Typhon Docs](/DOCS.md)  
[<< Return to Volcano Docs](./index.md)  

# Volcanic Ash
Volcanic Ash is the ash that is ejected to the sky from the volcano during the eruption such as `"vulcanian"`, `"plinian"`, `"surtseyan"` etc.

In Typhon Plugin, you can trigger the volcanic ash to be ejected from the volcano is implemented via `BlockDisplay` entities.

The following is implemented:
- `Pyroclastic Flows`
- `Ash Plumes`

## Pyroclastic Flows
Pyroclastic Flows are the fast-moving current of hot gas and volcanic matter that flows down the side of the volcano during the eruption.

In Typhon Plugin, The `Pyroclastic Flows` will race down the side of the volcano and destroy everything in its path and do the following:
- `Pile up Ash`: The pyroclastic flows will pile up the `TUFF` blocks on the ground.
- `Burn the Blocks`: The pyroclastic flows will burn the blocks that are flammable such as wood, leaves, etc.
- `Burn the Trees`: The pyroclastic flows will burn the trees and turn them into `coal_block` as if contacted with lava.  
- `Burn the Players`: The pyroclastic flows will burn the players to death if they are inside of it.

## Ash Plumes
Ash Plumes are the plumes of ash that is ejected to the sky from the volcano during the eruption.

In Typhon Plugin, The `Ash Plumes` will rise to the sky and do the following:
- `Burn the entities`: The ash plumes will burn the entities that are inside of it.
