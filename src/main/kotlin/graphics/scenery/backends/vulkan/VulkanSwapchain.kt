package graphics.scenery.backends.vulkan

import graphics.scenery.Hub
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.SceneryPanel
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.glfw.GLFWWindowSizeCallback
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memFree
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR
import org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.*

/**
 * GLFW-based default Vulkan Swapchain and window.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class VulkanSwapchain(open val device: VulkanDevice,
                           open val queue: VkQueue,
                           open val commandPool: Long,
                           @Suppress("unused") open val renderConfig: RenderConfigReader.RenderConfig,
                           open val useSRGB: Boolean = true) : Swapchain {

    protected val logger by LazyLogger()

    override var handle: Long = 0L
    override var images: LongArray? = null
    override var imageViews: LongArray? = null

    override var format: Int = 0

    var swapchainImage: IntBuffer = MemoryUtil.memAllocInt(1)
    var swapchainPointer: LongBuffer = MemoryUtil.memAllocLong(1)
    var presentInfo: VkPresentInfoKHR = VkPresentInfoKHR.calloc()
    lateinit var presentQueue: VkQueue

    open var surface: Long = 0
    lateinit var window: SceneryWindow
    lateinit var windowSizeCallback: GLFWWindowSizeCallback

    var lastResize = -1L
    private val WINDOW_RESIZE_TIMEOUT = 200 * 10e6

    private val retiredSwapchains: Queue<Pair<VulkanDevice, Long>> = ArrayDeque()

    data class ColorFormatAndSpace(var colorFormat: Int = 0, var colorSpace: Int = 0)

    override fun createWindow(win: SceneryWindow, swapchainRecreator: VulkanRenderer.SwapchainRecreator): SceneryWindow {
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)

        window = SceneryWindow.GLFWWindow(glfwCreateWindow(win.width, win.height, "scenery", MemoryUtil.NULL, MemoryUtil.NULL)).apply {
            width = win.width
            height = win.height

            glfwSetWindowPos(window, 100, 100)

            surface = VU.getLong("glfwCreateWindowSurface",
                { GLFWVulkan.glfwCreateWindowSurface(device.instance, window, null, this) }, {})

            // Handle canvas resize
            windowSizeCallback = object : GLFWWindowSizeCallback() {
                override operator fun invoke(glfwWindow: Long, w: Int, h: Int) {
                    if (lastResize > 0L && lastResize + WINDOW_RESIZE_TIMEOUT < System.nanoTime()) {
                        lastResize = System.nanoTime()
                        return
                    }

                    if (width <= 0 || height <= 0)
                        return

                    width = w
                    height = h

                    swapchainRecreator.mustRecreate = true
                    lastResize = -1L
                }
            }

            glfwSetWindowSizeCallback(window, windowSizeCallback)
            glfwShowWindow(window)
        }

        return window
    }

    override fun create(oldSwapchain: Swapchain?): Swapchain {
        return stackPush().use { stack ->
            val colorFormatAndSpace = getColorFormatAndSpace()
            val oldHandle = oldSwapchain?.handle

            // Get physical device surface properties and formats
            val surfCaps = VkSurfaceCapabilitiesKHR.callocStack(stack)

            VU.run("Getting surface capabilities",
                { KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physicalDevice, surface, surfCaps) })

            val presentModeCount = VU.getInts("Getting present mode count", 1,
                { KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice, surface, this, null) })

            val presentModes = VU.getInts("Getting present modes", presentModeCount.get(0),
                { KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice, surface, presentModeCount, this) })

            // Try to use mailbox mode. Low latency and non-tearing
            var swapchainPresentMode = KHRSurface.VK_PRESENT_MODE_FIFO_KHR

            for (i in 0 until presentModeCount.get(0)) {
                if (presentModes.get(i) == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) {
                    swapchainPresentMode = KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR
                    break
                }
                if (swapchainPresentMode != KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR && presentModes.get(i) == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR) {
                    swapchainPresentMode = KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR
                }
            }

            // Determine the number of images
            var desiredNumberOfSwapchainImages = surfCaps.minImageCount() + 1
            if (surfCaps.maxImageCount() in 1..(desiredNumberOfSwapchainImages - 1)) {
                desiredNumberOfSwapchainImages = surfCaps.maxImageCount()
            }

            val currentWidth = surfCaps.currentExtent().width()
            val currentHeight = surfCaps.currentExtent().height()

            if (currentWidth > 0 && currentHeight > 0) {
                window.width = currentWidth
                window.height = currentHeight
            } else {
                // TODO: Better default values
                window.width = 1920
                window.height = 1200
            }

            val preTransform = if (surfCaps.supportedTransforms() and KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR != 0) {
                KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            } else {
                surfCaps.currentTransform()
            }

            val swapchainCI = VkSwapchainCreateInfoKHR.callocStack(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .pNext(MemoryUtil.NULL)
                .surface(surface)
                .minImageCount(desiredNumberOfSwapchainImages)
                .imageFormat(colorFormatAndSpace.colorFormat)
                .imageColorSpace(colorFormatAndSpace.colorSpace)
                .imageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .preTransform(preTransform)
                .imageArrayLayers(1)
                .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .pQueueFamilyIndices(null)
                .presentMode(swapchainPresentMode)
                .clipped(true)
                .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)

            if ((oldSwapchain is VulkanSwapchain || oldSwapchain is FXSwapchain) && oldHandle != null) {
                swapchainCI.oldSwapchain(oldHandle)
            }

            swapchainCI.imageExtent().width(window.width).height(window.height)

            handle = VU.getLong("Creating swapchain",
                { KHRSwapchain.vkCreateSwapchainKHR(device.vulkanDevice, swapchainCI, null, this) }, {})

            // If we just re-created an existing swapchain, we should destroy the old swapchain at this point.
            // Note: destroying the swapchain also cleans up all its associated presentable images once the platform is done with them.
            if (oldSwapchain is VulkanSwapchain && oldHandle != null && oldHandle != VK10.VK_NULL_HANDLE) {
                // TODO: Figure out why deleting a retired swapchain crashes on Nvidia
//                KHRSwapchain.vkDestroySwapchainKHR(device.vulkanDevice, oldHandle, null)
                retiredSwapchains.add(device to oldHandle)
            }

            val imageCount = VU.getInts("Getting swapchain images", 1,
                { KHRSwapchain.vkGetSwapchainImagesKHR(device.vulkanDevice, handle, this, null) })

            logger.debug("Got ${imageCount.get(0)} swapchain images")

            val swapchainImages = VU.getLongs("Getting swapchain images", imageCount.get(0),
                { KHRSwapchain.vkGetSwapchainImagesKHR(device.vulkanDevice, handle, imageCount, this) }, {})

            val images = LongArray(imageCount.get(0))
            val imageViews = LongArray(imageCount.get(0))
            val colorAttachmentView = VkImageViewCreateInfo.callocStack(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .pNext(MemoryUtil.NULL)
                .format(colorFormatAndSpace.colorFormat)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                .flags(0)

            colorAttachmentView.components()
                .r(VK10.VK_COMPONENT_SWIZZLE_R)
                .g(VK10.VK_COMPONENT_SWIZZLE_G)
                .b(VK10.VK_COMPONENT_SWIZZLE_B)
                .a(VK10.VK_COMPONENT_SWIZZLE_A)

            colorAttachmentView.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            with(VU.newCommandBuffer(device, commandPool, autostart = true)) {
                for (i in 0 until imageCount.get(0)) {
                    images[i] = swapchainImages.get(i)

                    VU.setImageLayout(this, images[i],
                        aspectMask = VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                        oldImageLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                        newImageLayout = KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    colorAttachmentView.image(images[i])

                    imageViews[i] = VU.getLong("create image view",
                        { VK10.vkCreateImageView(device.vulkanDevice, colorAttachmentView, null, this) }, {})
                }

                this.endCommandBuffer(device, commandPool, queue,
                    flush = true, dealloc = true)
            }

            this.images = images
            this.imageViews = imageViews
            this.format = colorFormatAndSpace.colorFormat

            memFree(swapchainImages)
            memFree(imageCount)
            memFree(presentModeCount)
            memFree(presentModes)

            this
        }
    }

    private fun getColorFormatAndSpace(): ColorFormatAndSpace {
        return stackPush().use { stack ->
            val queueFamilyPropertyCount = stack.callocInt(1)
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.physicalDevice, queueFamilyPropertyCount, null)

            val queueCount = queueFamilyPropertyCount.get(0)
            val queueProps = VkQueueFamilyProperties.callocStack(queueCount, stack)
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.physicalDevice, queueFamilyPropertyCount, queueProps)

            // Iterate over each queue to learn whether it supports presenting:
            val supportsPresent = (0 until queueCount).map {
                VU.getInt("Physical device surface support",
                    { KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device.physicalDevice, it, surface, this) })
            }

            // Search for a graphics and a present queue in the array of queue families, try to find one that supports both
            var graphicsQueueNodeIndex = Integer.MAX_VALUE
            var presentQueueNodeIndex = Integer.MAX_VALUE

            for (i in 0 until queueCount) {
                if (queueProps.get(i).queueFlags() and VK10.VK_QUEUE_GRAPHICS_BIT != 0) {
                    if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
                        graphicsQueueNodeIndex = i
                    }
                    if (supportsPresent[i] == VK10.VK_TRUE) {
                        graphicsQueueNodeIndex = i
                        presentQueueNodeIndex = i
                        break
                    }
                }
            }

            if (presentQueueNodeIndex == Integer.MAX_VALUE) {
                // If there's no queue that supports both present and graphics try to find a separate present queue
                for (i in 0 until queueCount) {
                    if (supportsPresent[i] == VK10.VK_TRUE) {
                        presentQueueNodeIndex = i
                        break
                    }
                }
            }

            // Generate error if could not find both a graphics and a present queue
            if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
                throw RuntimeException("No graphics queue found")
            }
            if (presentQueueNodeIndex == Integer.MAX_VALUE) {
                throw RuntimeException("No presentation queue found")
            }
            if (graphicsQueueNodeIndex != presentQueueNodeIndex) {
                throw RuntimeException("Presentation queue != graphics queue")
            }

            presentQueue = VkQueue(VU.getPointer("Get present queue",
                { VK10.vkGetDeviceQueue(device.vulkanDevice, presentQueueNodeIndex, 0, this); VK10.VK_SUCCESS }, {}),
                device.vulkanDevice)

            // Get list of supported formats
            val formatCount = VU.getInts("Getting supported surface formats", 1,
                { KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice, surface, this, null) })

            val surfFormats = VkSurfaceFormatKHR.callocStack(formatCount.get(0), stack)
            VU.run("Query device physical surface formats",
                { KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice, surface, formatCount, surfFormats) })

            val colorFormat = if (formatCount.get(0) == 1 && surfFormats.get(0).format() == VK10.VK_FORMAT_UNDEFINED) {
                if (useSRGB) {
                    VK10.VK_FORMAT_B8G8R8A8_SRGB
                } else {
                    VK10.VK_FORMAT_B8G8R8A8_UNORM
                }
            } else {
                if (useSRGB) {
                    VK10.VK_FORMAT_B8G8R8A8_SRGB
                } else {
                    VK10.VK_FORMAT_B8G8R8A8_UNORM
                }
            }

            val colorSpace = if (useSRGB) {
                KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
            } else {
                surfFormats.get(0).colorSpace()
            }

            memFree(formatCount)

            ColorFormatAndSpace(colorFormat, colorSpace)
        }
    }

    override fun present(waitForSemaphores: LongBuffer?) {
        // Present the current buffer to the swap chain
        // This will display the image
        swapchainPointer.put(0, handle)

        // Info struct to present the current swapchain image to the display
        presentInfo
            .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
            .pNext(MemoryUtil.NULL)
            .swapchainCount(swapchainPointer.remaining())
            .pSwapchains(swapchainPointer)
            .pImageIndices(swapchainImage)
            .pResults(null)

        waitForSemaphores?.let { presentInfo.pWaitSemaphores(it) }

        // here we accept the VK_ERROR_OUT_OF_DATE_KHR error code, which
        // seems to spuriously occur on Linux upon resizing.
        VU.run("Presenting swapchain image",
            { KHRSwapchain.vkQueuePresentKHR(presentQueue, presentInfo) },
            allowedResults = listOf(VK_ERROR_OUT_OF_DATE_KHR))
    }

    override fun postPresent(image: Int) {
        while(retiredSwapchains.isNotEmpty()) {
            retiredSwapchains.poll()?.let {
                KHRSwapchain.vkDestroySwapchainKHR(it.first.vulkanDevice, it.second, null)
            }
        }
    }

    override fun next(timeout: Long, waitForSemaphore: Long): Boolean {
        // wait for the present queue to become idle - by doing this here
        // we avoid stalling the GPU and gain a few FPS
        VK10.vkQueueWaitIdle(presentQueue)

        val err = vkAcquireNextImageKHR(device.vulkanDevice, handle, timeout,
            waitForSemaphore,
            VK10.VK_NULL_HANDLE, swapchainImage)

        if (err == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || err == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
            return true
        } else if (err != VK10.VK_SUCCESS) {
            throw AssertionError("Failed to acquire next swapchain image: " + VU.translate(err))
        }

        return false
    }

    override fun toggleFullscreen(hub: Hub, swapchainRecreator: VulkanRenderer.SwapchainRecreator) {
        (window as SceneryWindow.GLFWWindow?)?.let { window ->
            if (window.isFullscreen) {
                glfwSetWindowMonitor(window.window,
                    MemoryUtil.NULL,
                    0, 0,
                    window.width, window.height, GLFW_DONT_CARE)
                glfwSetWindowPos(window.window, 100, 100)
                glfwSetInputMode(window.window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)

                swapchainRecreator.mustRecreate = true
                window.isFullscreen = false
            } else {
                val preferredMonitor = System.getProperty("scenery.FullscreenMonitor", "0").toInt()

                val monitor = if (preferredMonitor == 0) {
                    glfwGetPrimaryMonitor()
                } else {
                    val monitors = glfwGetMonitors()
                    if (monitors != null && monitors.remaining() >= preferredMonitor) {
                        monitors.get(preferredMonitor)
                    } else {
                        glfwGetPrimaryMonitor()
                    }
                }

                val hmd = hub.getWorkingHMDDisplay()

                if (hmd != null) {
                    window.width = hmd.getRenderTargetSize().x().toInt() / 2
                    window.height = hmd.getRenderTargetSize().y().toInt()
                    logger.info("Set fullscreen window dimensions to ${window.width}x${window.height}")
                }

                glfwSetWindowMonitor(window.window,
                    monitor,
                    0, 0,
                    window.width, window.height, GLFW_DONT_CARE)
                glfwSetInputMode(window.window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN)

                swapchainRecreator.mustRecreate = true
                window.isFullscreen = true
            }
        }
    }

    override fun embedIn(panel: SceneryPanel?) {
        if(panel == null) {
            return
        }

        logger.error("Embedding is not supported with the default Vulkan swapchain. Use FXSwapchain instead.")
    }

    override fun close() {
        logger.debug("Closing swapchain $this")
        KHRSwapchain.vkDestroySwapchainKHR(device.vulkanDevice, handle, null)

        presentInfo.free()
        MemoryUtil.memFree(swapchainImage)
        MemoryUtil.memFree(swapchainPointer)

        windowSizeCallback.close()
        (window as SceneryWindow.GLFWWindow?)?.let { window -> glfwDestroyWindow(window.window) }
    }
}
