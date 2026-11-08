paper 1.21.11 plugin 

resource pack in resourcepack/

build with mvn package in plugin/ and put the jar in plugins
put resourcepack/cannons-resourcepack.zip in .minecraft/resourcepacks

commands (op)

    /cannon give cannon|makeshift|ballista|mounted
    /cannon plans
    /cannon remove
    /cannon reload
    /cannon calibrate


build plans

craft a plan and place it on a 3x3 of solid ground then right click it to build the weapon
hit it to get the plan back
BuildPlan.java has the items recipes and building zone
the plan is a trial key with a custom model so it cant be crafted or traded

the 3x3 under a weapon is its foundation (Foundation.java)
those blocks resist explosions like deepslate bricks
if one breaks the weapon explodes
it is saved in chunk data


cannons

right click to sit and aim with your view
F fires since swap hands is sent even with empty hands
E or the muzzle opens the crate

the crate is a 3 row chest with a font glyph over it as the title
tools/gui.py lines tools/models/cannon_rack.png up with the slots and writes the font
the pack hides the Inventory label

left column of the lid

    top       gunpowder for speed (max: 20)
    middle    gunpowder that causes flames on impact (max: 5)
    bottom    gunpowder that causes smoke cloud on impact. gives blindness and nausea for a few seconds (max: 5)

right column is ammo and any block works
the columns next to them are storage that refills after each shot
tall slots are health and a readout and the middle slot is disassemble

20 speed gunpowder goes about 380 blocks at 45 degrees
tnt as ammo clears about 10x the normal number blocks on impact
above 8 speed powder there is a misfire chance up to 10% at 20 (20% on the makeshift)
a misfire explodes the cannon with power 6
flame powder sets how much fire the blast leaves
smoke powder leaves a cloud that blinds and gives nausea (SmokeCloud.java)
AmmoInventory.java and CannonListener.java handle the crate and firing

CannonBall.java flies a BlockDisplay with its own gravity and drag
it raytraces each step and loads chunks ahead on long shots


ballistas

right click to sit then right click with an arrow or firework rocket to load and F to fire
the mounted ballista fires 5 from one item and they fly 50% faster
BallistaBolt.java is the arrow drawn as two crossed sprites
BallistaRocket.java flies straight and explodes at half tnt power on impact or when the crossbow fuse runs out
it does 4x crossbow damage and leaves a flame trail


health

WeaponHealth.java gives each weapon health
cannonballs rockets bolts tnt and creepers damage it
disassemble drains health over 15 seconds then it explodes like tnt
the ballista menu opens with sneak right click (Ohmic_flicker_the_goat.java)
weapon explosions leave fire on cleared blocks


models

each weapon is display entities on an Interaction anchor that is the hitbox and holds saved data
each display shows one model piece with a custom item model

tools/convert.py splits the cannon bbmodels into one item model per bone and a table for CannonType.java
tools/rig.py turns the ballista bbmodels into item models and a rig file of bones and keyframes
BallistaRig.java plays the rig every tick and Ballista.java runs the wind and release

java models only rotate in 22.5 degree steps on one axis so other rotations go on the display
item displays render turned 180 so the converter pre rotates the models

the cannon seat is an invisible horse and the ballista seat is an armor stand
their max health is under 1.5 so no mount hearts show


config

config.yml has seat type turn-speed health per weapon and disassemble-seconds
custom-models false uses plain blocks without the pack
