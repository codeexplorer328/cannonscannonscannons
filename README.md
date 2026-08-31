rideable cannons for paper 1.21.11. two models and both need the resource pack

    plugin/         run mvn package and the jar ends up in plugin/target
    resourcepack/   goes in .minecraft/resourcepacks
    tools/          convert.py rebuilds the pack from the bbmodels

commands:

    /cannon give cannon
    /cannon give makeshift
    /cannon remove
    /cannon reload
    /cannon calibrate

how to use it:

right click the body to sit. press E for the ammo rack. the barrel follows
where you look. hold space and let go to set the charge then right click to
fire. an empty bar goes 8 blocks and a full one goes 50. hits like tnt

the stick you get while seated is on purpose. the client sends nothing for an
empty hand right click on air so it needs something to hold (will remove in future)

notes:

set custom-models to false in the config to render with plain blocks
every right click while seated logs a fire line in the console
max range is about 35 degrees not 45 because of drag
the makeshift bbmodel has a recoil animation that isnt used yet
