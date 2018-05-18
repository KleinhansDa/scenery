[![scenery logo](./artwork/logo-light-small.png)](./artwork/logo-light.png)

----
-__Hello, this is the master branch, where development happens and stuff breaks.__ If you require a stable version of scenery, please check out the [latest stable version of this repository, version 0.4.1](https://github.com/scenerygraphics/scenery/tree/scenery-0.4.1).
-
----
-

[![Travis Build Status](https://travis-ci.org/scenerygraphics/scenery.svg?branch=master)](https://travis-ci.org/scenerygraphics/scenery) [![Appveyor Build status](https://ci.appveyor.com/api/projects/status/vysiatrptqas4cfy?svg=true)](https://ci.appveyor.com/project/skalarproduktraum/scenery)  [![Join the chat at https://gitter.im/ClearVolume/ClearVolume](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/ClearVolume/ClearVolume?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) [![DOI](https://zenodo.org/badge/49890276.svg)](https://zenodo.org/badge/latestdoi/49890276)

# scenery  // flexible scenegraphing and rendering for scientific visualisation

![Blood Cells Example](https://ulrik.is/scenery-bloodcells.gif)


## Synopsis

scenery is a scenegraphing and rendering library. It allows you to quickly create high-quality 3D visualisations based on mesh data. scenery contains both a OpenGL 4.1 and Vulkan renderer. The rendering pipelines of both renderers are configurable using YAML files, so it's easy to switch between e.g. [Forward Shading](./src/main/resources/graphics/scenery/backends/ForwardShading.yml) and [Deferred Shading](./src/main/resources/graphics/scenery/backends/DeferredShading.yml), as well as [stereo rendering](./src/main/resources/graphics/scenery/backends/DeferredShadingStereo.yml). Rendering pipelines can be switched on-the-fly.

Both renderers support rendering to head-mounted VR goggles like the HTC Vive or Oculus Rift via [OpenVR/SteamVR](https://github.com/ValveSoftware/openvr).

## Examples

Have a look in the [src/test/tests/graphics/scenery/tests/examples](./src/test/tests/graphics/scenery/tests/examples/) directory, there you'll find plenty of examples how to use _scenery_ in Kotlin, and a few Java examples.

Some of the examples need additional meshes, which are not part of the repository due to their size. These meshes can be downloaded [here](https://ulrik.is/scenery-demo-models.zip) and extracted to a directory of choice. When running the examples, the environment variable `SCENERY_DEMO_FILES` should point to this directory, otherwise the models will not be loaded and scenery will complain.

## Contributed examples

* Scala - [@Sciss](https://github.com/Sciss) has translated the Kotlin and Java examples to Scala, [https://github.com/Sciss/SceneryScalaExamples](https://github.com/Sciss/SceneryScalaExamples)

## Default Key bindings

Most of the demos use the following key bindings:

### Movement
| Key | Action |
| --- | --- |
| Mouse drag | Look-around |
| `W, A, S, D` | Move forward, left, back, right |
| `Shift` - `W, A, S, D` | Move forward, left, back, right fast |
| `Space` | Move upwards |
| `Shift` - `Space` | Move downwards |
| `C` | Switch between FPS and Arcball camera control modes (only used in `SponzaExample`) |

If a gamepad is connected (such as a PlayStation 3 or 4 controller), the hats can be used for movement and look-around.

### Rendering
| Key | Action |
| --- | --- |
| `K`, `L` | Increase/decrease exposure for HDR rendering |
| `Shift`-`K`, `Shift-L` | Increase/decrease gamma for HDR rendering |
| `F` | Toggle fullscreen |
| `Q` | Toggle deferred shading buffer debug view |
| `O` | Toggle SSAO (Screen-space ambient occlusion |
| `P` | Save screenshot to Desktop as PNG |

All keybindings are also listed in the [InputHandler class](./src/main/kotlin/graphics/scenery/controls/InputHandler.kt#L198).

## Selecting a renderer
On Windows and Linux, scenery defaults to using the high-performance Vulkan renderer, while on macOS, it'll default to the OpenGL renderer.

If you would like to override this, set the system property `scenery.Renderer` to either `VulkanRenderer` or `OpenGLRenderer`. 

If you want to use Vulkan validation layers, or select a different graphics card than the primary one, please consult the [VulkanRenderer README](./src/main/kotlin/graphics/scenery/backends/vulkan/README.md).

scenery has been tested with MoltenVK on macOS, but there are some major issues remaining before Vulkan can also be used on macOS.

## Building

Into a directory of your choice, clone the Git repository of scenery:

```shell
git clone https://github.com/scenerygraphics/scenery
```

Then, change to the newly created `scenery` directory, and run `mvn clean install` to build and install both packages into your local Maven repository.

Alternatively, scenery's Maven project can be imported into IntelliJ or Eclipse. Please note that Eclipse needs the [Kotlin plugin from JetBrains](https://github.com/JetBrains/kotlin-eclipse) to work correctly.

If you want to compile the provided shader files offline on your own, please make sure you have the [latest Vulkan SDK from LunarG](https://vulkan.lunarg.com) installed. At least version 1.1.70 is required.

## Using _scenery_ in a project

### Maven artifacts

Artifacts are currently published to the Sonatype OSS repository, and synchronised with Maven Central. For all commits on master, a new SNAPSHOT build is automatically created.

### Using _scenery_ in a Maven project

Add scenery and ClearGL to your project's `pom.xml`:

```xml
<dependencies>
  <dependency>
    <groupId>graphics.scenery</groupId>
    <artifactId>scenery</artifactId>
    <version>0.4.2-SNAPSHOT</version>
  </dependency>

  <dependency>
    <groupId>net.clearvolume</groupId>
    <artifactId>cleargl</artifactId>
    <version>2.1.6</version>
  </dependency>
</dependencies>
```

### Using _scenery_ in a Gradle project

Add scenery and ClearGL to your project's `build.gradle`:

```groovy
compile group: 'graphics.scenery', name: 'scenery', version: '0.4.2-SNAPSHOT'
compile group: 'net.clearvolume', name: 'cleargl', version: '2.1.6'
```

### Logging

scenery uses [slf4j](https://slf4j.org) for logging. If you use scenery in your own library and want to see scenery's messages, you need to have a logger (e.g. `slf4j-simple`) configured in your project. Check [this page](https://www.slf4j.org/manual.html) on how to do that.

To configure the logging level that scenery uses, set the system property `scenery.LogLevel` to `info` (default), `warn`, `debug` or `trace`. Be advised that both `debug` and `trace` produce a lot of output and thereby negatively affect performance.

## GPU compatibility

scenery has been tested with a number of different systems and GPUs. If you have a setup that is not listed in the following table - or marked as untested - please submit a PR with the setup added.

✅ Works
⛔ Does not work
⬜ Untested
🚫 Unsupported configuration (e.g. no driver support)

| GPU | Windows, OpenGL | Windows, Vulkan | Linux, OpenGL | Linux, Vulkan | Mac OS X, OpenGL |
|:--|:--|:--|:--|:--|:--|
| AMD Radeon R5 M230 (Caicos Pro) | ⛔ | ✅ | ⬜ | ⬜ | ⬜ |
| AMD Radeon R9 390 (Hawaii Pro) | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| AMD Radeon R9 Nano (Fiji XT) | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| AMD Radeon R9 M370X (Strato Pro) | ⬜ | ⬜ | ⬜ | ⬜ | ✅ |
| AMD FirePro W9100 (Hawaii XT) | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Intel HD Graphics 4400 (Haswell) | ✅ | 🚫 | ✅ | ✅ | ⬜ |
| Intel HD Graphics 5500 (Broadwell) | ⬜ | 🚫 | ✅ | ⬜ | ⬜ |
| Nvidia Geforce Titan X (Maxwell) | ✅ | ✅ | ✅ | ✅ | ⬜	 |
| Nvidia Titan Xp (Pascal) | ✅ | ✅ | ⬜ | ⬜	 | ⬜	 |
| Nvidia Geforce 1080 Ti (Pascal) | ✅ | ✅ | ✅ | ✅| ⬜	 |
| Nvidia Geforce 1070 (Pascal) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Nvidia Geforce 960 (Maxwell) | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Nvidia Quadro K6000 (Kepler) | ✅ | ✅ | ⬜ | ⬜ | ⬜ |
| Nvidia Quadro P5000 (Pascal) | ⬜ | ⬜ | ✅ | ⬜ | ⬜ |
| Nvidia Geforce 750M (Kepler) | ✅  | ✅  | ⬜ | ⬜ | ✅  |
| Nvidia Geforce 650M (Kepler) | ⬜  | ⬜  | ⬜ | ⬜ | ✅  |

Please also note that Nvidia's Vulkan drivers before version 382.33 have a bug that prevents scenery's Vulkan renderer from working correctly.
