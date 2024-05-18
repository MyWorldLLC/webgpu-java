# WebGPU Bindings for Java

This project automatically generates and packages Panama bindings for the
[wgpu-native](https://github.com/gfx-rs/wgpu-native) implementation of 
[WebGPU's native C API](https://github.com/webgpu-native/webgpu-headers/).
This is an automated process - everytime there is a new release of `wgpu-native`,
a GitHub Action in this repository generates and publishes a corresponding binding.

The built jar files are published as Maven artifacts in the 
[MyWorld Package Repository](https://github.com/MyWorldLLC/Packages/packages/2097504).
Note that it is currently the case that you are responsible for acquiring and packaging
`wgpu-native` builds for your target platform(s), however, support for publishing these
libraries as `jlink`-able modules is planned.