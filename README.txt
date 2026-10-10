GO FAST - Fabric mod for Minecraft Java 1.21.1 (source project)

ABILITIES
  Rings          Carry rings as health. A hit costs you up to 20 rings, which scatter so you can pick them up again.
                 With no rings you take normal damage.
  Boost Dash     Right-click the Boost Orb to dash where you look (1.5 second cooldown).
  Spin Dash      Hold the Boost Orb, stand on the ground, hold SNEAK to charge, release to launch.
  Homing Attack  In mid-air, right-click the Boost Orb with a hostile mob in front of you to lock on and strike it.
  Dash Pad       Step on the block to be launched forward.
  Super Form     Hold a stack of 7 Chaos Emeralds with 50+ rings, right-click. You can fly, are invincible, and move
                 and jump much faster, but it costs 1 ring per second. Right-click again to cancel.

RECIPES
  1 gold ingot                          -> 8 rings
  ender pearl + gold ingot              -> Boost Orb
  2 iron ingots + redstone + ring       -> 2 Dash Pads
  diamond + emerald + gold ingot        -> 1 Chaos Emerald (you need 7)

NOT INCLUDED (these need client-side code, which is a bigger project)
  Double jump, drop dash, wall running, grind rails, loops, and a custom player model.

BUILD (needs Java 21)
  Easiest: go to https://fabricmc.net/develop/template , pick Minecraft 1.21.1, mod id "gofast",
  download and unzip it, then copy this project's "src" folder over the template's "src" folder.
  Run:  ./gradlew build     (Windows: gradlew.bat build)
  Your mod is build/libs/gofast-1.0.0.jar -> put it in .minecraft/mods along with Fabric API.
