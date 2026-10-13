# Contributing

The most useful things right now:

1. **Device reports.** Use the "Device report" issue template, or open an issue with your phone model, chip, Android version, whether the engine started on the GPU, and the log from the Engine tab ("Copy log"). The app has run on one phone so far.
2. **Speed.** Rendering is the slow stage. Ideas, measurements and patches are welcome; please include the GPU clock (`/sys/class/kgsl/kgsl-3d0/max_gpuclk`) beside any timing, because a warm phone can be several times slower than a cool one.
3. **Bugs in the app**, with steps to reproduce.

Code style: plain Java 8 without lambdas (the build uses the classic dx tool), no external libraries, and comments that explain why rather than what.

By contributing you agree that your contribution is licensed under the Apache License 2.0.

Questions that should not be public can go to jackknifeai@proton.me.
