# Contributing

The most useful things right now:

1. **Device reports.** Use the "Device report" issue template, or open an issue with your phone model, chip, Android version, whether the engine started on the GPU, and the log from the Engine tab ("Copy log"). The app has run on one phone so far.
2. **Speed.** Rendering is the slow stage. Ideas, measurements and patches are welcome; please include the GPU clock (`/sys/class/kgsl/kgsl-3d0/max_gpuclk`) beside any timing, because a warm phone can be several times slower than a cool one.
3. **Bugs in the app**, with steps to reproduce.

Code style: plain Java 8 without lambdas (the build uses the classic dx tool), no external libraries, and comments that explain why rather than what.

By contributing you agree that your contribution is licensed under the Apache License 2.0.

Questions that should not be public can go to jackknifeai@proton.me.

## Keep the in-app guide up to date

The app explains itself in `app/assets/guide.txt`, opened from Engine > How everything works. It covers every
setting and every button: Create and its advanced settings, Library and the player, Lyrics, Engine, and the
Studio (Vault, Arrange, Mixer, Stems, Export).

**Every pull request that adds, removes or changes a setting, button or gesture updates the guide in the same
pull request.** Say what the control does and when to use it, in plain words, and give its default when it
has one. Do not promise speeds or results that have not been measured on a phone. The format is simple:
`# ` starts a part, `## ` a control, `- ` a list item, any other line is a paragraph.
