package com.myworldvw.webgpu.demo;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import io.github.libsdl4j.api.event.SDL_Event;
import io.github.libsdl4j.api.syswm.SDL_SysWMInfo;
import io.github.libsdl4j.api.video.SDL_Window;

import java.lang.foreign.*;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.myworldvw.webgpu.webgpu_h.*;
import static io.github.libsdl4j.api.Sdl.*;
import static io.github.libsdl4j.api.SdlSubSystemConst.SDL_INIT_EVERYTHING;
import static io.github.libsdl4j.api.error.SdlError.SDL_GetError;
import static io.github.libsdl4j.api.event.SDL_EventType.*;
import static io.github.libsdl4j.api.event.SdlEvents.SDL_PollEvent;
import static io.github.libsdl4j.api.syswm.SdlSysWM.SDL_GetWindowWMInfo;
import static io.github.libsdl4j.api.version.SdlVersion.SDL_GetJavaBindingsVersion;
import static io.github.libsdl4j.api.video.SDL_WindowEventID.SDL_WINDOWEVENT_RESIZED;
import static io.github.libsdl4j.api.video.SDL_WindowEventID.SDL_WINDOWEVENT_SIZE_CHANGED;
import static io.github.libsdl4j.api.video.SDL_WindowFlags.*;
import static io.github.libsdl4j.api.video.SdlVideo.SDL_CreateWindow;
import static io.github.libsdl4j.api.video.SdlVideo.SDL_GetWindowSize;
import static io.github.libsdl4j.api.video.SdlVideoConst.SDL_WINDOWPOS_CENTERED;

import com.myworldvw.webgpu.*;

public class Main {

    private SDL_Window window;

    private void init(){

        // Create and init the window
        window = SDL_CreateWindow("Hello World!", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, 200, 200, SDL_WINDOW_SHOWN | SDL_WINDOW_RESIZABLE);
        if (window == null) {
            throw new IllegalStateException("Unable to create SDL window: " + SDL_GetError());
        }

        System.load(
                Path.of("libwgpu_native.so").toAbsolutePath().toString());

        try(var arena = Arena.ofConfined()){

            var descriptor = WGPUInstanceDescriptor.allocate(arena);
            WGPUInstanceDescriptor.nextInChain$VH().set(descriptor, MemorySegment.NULL);

            var instance = wgpuCreateInstance(descriptor);
            if(MemorySegment.NULL.equals(instance)){
                System.out.println("Failed to create WGPU instance!");
            }

            var wmInfo = new SDL_SysWMInfo();
            wmInfo.version = SDL_GetJavaBindingsVersion();
            if(!SDL_GetWindowWMInfo(window, wmInfo)){
                System.out.println("Failed to get SDL window info!");
            }

            // TODO - demo surfaces for other platforms. The same basic idea applies
            // regardless - obtain a native handle, create a corresponding struct,
            // and chain to it from the surface descriptor.
            var display = wmInfo.info.x11.display;
            var x11Window = wmInfo.info.x11.window;
            var nativeDisplay = Pointer.nativeValue(display);

            MemorySegment fromXlibWindow = WGPUSurfaceDescriptorFromXlibWindow.allocate(arena);
            WGPUChainedStruct.next$set(fromXlibWindow, MemorySegment.NULL);
            WGPUChainedStruct.sType$set(WGPUSurfaceDescriptorFromXlibWindow.chain$slice(fromXlibWindow),
                    WGPUSType_SurfaceDescriptorFromXlibWindow());

            WGPUSurfaceDescriptorFromXlibWindow.window$set(fromXlibWindow, x11Window.longValue());
            WGPUSurfaceDescriptorFromXlibWindow.display$set(fromXlibWindow, MemorySegment.ofAddress(nativeDisplay));

            var surfaceDescriptor = WGPUSurfaceDescriptor.allocate(arena);
            WGPUSurfaceDescriptor.nextInChain$set(surfaceDescriptor, fromXlibWindow);
            WGPUSurfaceDescriptor.label$set(surfaceDescriptor, MemorySegment.NULL);

            var surface = wgpuInstanceCreateSurface(instance, surfaceDescriptor);
            if(MemorySegment.NULL.equals(surface)){
                System.out.println("WGPU surface is null!");
            }

            var adapterRequestOptions = WGPURequestAdapterOptions.allocate(arena);
            WGPURequestAdapterOptions.nextInChain$set(adapterRequestOptions, MemorySegment.NULL);
            WGPURequestAdapterOptions.compatibleSurface$set(adapterRequestOptions, surface);

            var adapterFuture = new CompletableFuture<AdapterCallbackResult>();
            var adapterCallback = WGPURequestDeviceCallback.allocate((status, device, message, userData) ->
                    adapterFuture.complete(new AdapterCallbackResult(
                        status,
                        device,
                        message.reinterpret(Long.MAX_VALUE).getUtf8String(0),
                        userData
                    )
            ), arena);

            wgpuInstanceRequestAdapter(instance, adapterRequestOptions, adapterCallback, MemorySegment.NULL);
            var adapterInfo = adapterFuture.get();

            var adapterFeatureCount = wgpuAdapterEnumerateFeatures(adapterInfo.adapter(), MemorySegment.NULL);

            var features = arena.allocateArray(ValueLayout.JAVA_INT, adapterFeatureCount);
            wgpuAdapterEnumerateFeatures(adapterInfo.adapter(), features);

            var deviceFuture = new CompletableFuture<DeviceCallbackResult>();
            var deviceCallback = WGPURequestDeviceCallback.allocate((status, device, message, userData) -> {
                deviceFuture.complete(new DeviceCallbackResult(
                        status,
                        device,
                        message.reinterpret(Long.MAX_VALUE).getUtf8String(0),
                        userData
                ));
            }, arena);

            var deviceDescriptor = WGPUDeviceDescriptor.allocate(arena);
            WGPUDeviceDescriptor.nextInChain$set(deviceDescriptor, MemorySegment.NULL);
            WGPUDeviceDescriptor.requiredFeatureCount$set(deviceDescriptor, 0);
            WGPUDeviceDescriptor.requiredLimits$set(deviceDescriptor, MemorySegment.NULL);

            var defaultQueue = WGPUDeviceDescriptor.defaultQueue$slice(deviceDescriptor);
            WGPUQueueDescriptor.label$set(defaultQueue, arena.allocateUtf8String("Default Queue"));

            wgpuAdapterRequestDevice(adapterInfo.adapter(), deviceDescriptor, deviceCallback, MemorySegment.NULL);

            var device = deviceFuture.get();
            var deviceErrorCallback = WGPUErrorCallback.allocate((type, message, userData) -> {
                System.out.println("Device error %d: %s".formatted(type, message.reinterpret(Long.MAX_VALUE).getUtf8String(0)));
            }, Arena.global());

            wgpuDeviceSetUncapturedErrorCallback(device.device(), deviceErrorCallback, MemorySegment.NULL);

            var queue = wgpuDeviceGetQueue(device.device());
            var workDoneCallback = WGPUQueueWorkDoneCallback.allocate((status, userData) -> {
                System.out.println("Queued work finished with status: " + status);
            }, Arena.global());

            wgpuQueueOnSubmittedWorkDone(queue, workDoneCallback, MemorySegment.NULL);

            var capabilities = WGPUSurfaceCapabilities.allocate(arena);
            wgpuSurfaceGetCapabilities(surface, adapterInfo.adapter(), capabilities);

            // Configure surface to use device
            var surfaceConfig = WGPUSurfaceConfiguration.allocate(arena);
            var format = wgpuSurfaceGetPreferredFormat(surface, adapterInfo.adapter());

            WGPUSurfaceConfiguration.presentMode$set(surfaceConfig, WGPUPresentMode_Fifo());
            WGPUSurfaceConfiguration.device$set(surfaceConfig, device.device());
            WGPUSurfaceConfiguration.format$set(surfaceConfig, format);
            WGPUSurfaceConfiguration.alphaMode$set(surfaceConfig, WGPUCompositeAlphaMode_Opaque());
            WGPUSurfaceConfiguration.height$set(surfaceConfig, 200);
            WGPUSurfaceConfiguration.width$set(surfaceConfig, 200);
            WGPUSurfaceConfiguration.usage$set(surfaceConfig, WGPUTextureUsage_RenderAttachment());
            wgpuSurfaceConfigure(surface, surfaceConfig);

            var nextTex = WGPUSurfaceTexture.allocate(arena);

            var gradient = arena.allocateArray(ValueLayout.JAVA_BYTE, 4 * 200 * 200);
            for(int i = 0; i < 200; i++){
                for(int j = 0; j < 200; j++){
                    int p = 4 * (j * 200 + i);
                    gradient.setAtIndex(ValueLayout.JAVA_BYTE, p, (byte)i);
                    gradient.setAtIndex(ValueLayout.JAVA_BYTE, p + 1, (byte)j);
                    gradient.setAtIndex(ValueLayout.JAVA_BYTE, p + 2, (byte)128);
                    gradient.setAtIndex(ValueLayout.JAVA_BYTE, p + 3, (byte)255);
                }
            }

            var shaderSrc = """      
                    @vertex
                    fn vs_main(@location(0) in_vertex_position: vec2f) -> @builtin(position) vec4f {
                        return vec4f(in_vertex_position, 0.0, 1.0);
                    }
                                        
                    @fragment
                    fn fs_main() -> @location(0) vec4f {
                        return vec4f(0.0, 0.4, 1.0, 1.0);
                    }
                    """;

            var shaderDesc = WGPUShaderModuleDescriptor.allocate(arena);
            WGPUShaderModuleDescriptor.hintCount$set(shaderDesc, 0);
            WGPUShaderModuleDescriptor.hints$set(shaderDesc, MemorySegment.NULL);

            var shaderCodeDesc = WGPUShaderModuleWGSLDescriptor.allocate(arena);
            WGPUChainedStruct.next$set(WGPUShaderModuleWGSLDescriptor.chain$slice(shaderCodeDesc), MemorySegment.NULL);
            WGPUChainedStruct.sType$set(WGPUShaderModuleWGSLDescriptor.chain$slice(shaderCodeDesc), WGPUSType_ShaderModuleWGSLDescriptor());

            WGPUShaderModuleDescriptor.nextInChain$set(shaderDesc, WGPUShaderModuleWGSLDescriptor.chain$slice(shaderCodeDesc));
            WGPUShaderModuleWGSLDescriptor.code$set(shaderCodeDesc, arena.allocateUtf8String(shaderSrc));

            var shaderModule = wgpuDeviceCreateShaderModule(device.device(), shaderDesc);

            var pipelineDesc = WGPURenderPipelineDescriptor.allocate(arena);
            WGPURenderPipelineDescriptor.nextInChain$set(pipelineDesc, MemorySegment.NULL);
            WGPUVertexState.bufferCount$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), 0);
            WGPUVertexState.buffers$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), MemorySegment.NULL);

            WGPUVertexState.module$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), shaderModule);
            WGPUVertexState.entryPoint$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), arena.allocateUtf8String("vs_main"));
            WGPUVertexState.constantCount$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), 0);
            WGPUVertexState.constants$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), MemorySegment.NULL);

            WGPUPrimitiveState.topology$set(WGPURenderPipelineDescriptor.primitive$slice(pipelineDesc), WGPUPrimitiveTopology_TriangleList());
            WGPUPrimitiveState.stripIndexFormat$set(WGPURenderPipelineDescriptor.primitive$slice(pipelineDesc), WGPUIndexFormat_Undefined());
            WGPUPrimitiveState.frontFace$set(WGPURenderPipelineDescriptor.primitive$slice(pipelineDesc), WGPUFrontFace_CCW());
            WGPUPrimitiveState.cullMode$set(WGPURenderPipelineDescriptor.primitive$slice(pipelineDesc), WGPUCullMode_None());

            var fragmentState = WGPUFragmentState.allocate(arena);
            WGPUFragmentState.module$set(fragmentState, shaderModule);
            WGPUFragmentState.entryPoint$set(fragmentState, arena.allocateUtf8String("fs_main"));
            WGPUFragmentState.constantCount$set(fragmentState, 0);
            WGPUFragmentState.constants$set(fragmentState, MemorySegment.NULL);
            WGPURenderPipelineDescriptor.fragment$set(pipelineDesc, fragmentState);

            WGPURenderPipelineDescriptor.depthStencil$set(pipelineDesc, MemorySegment.NULL);

            var blendState = WGPUBlendState.allocate(arena);
            var blendColor = WGPUBlendState.color$slice(blendState);
            WGPUBlendComponent.srcFactor$set(blendColor, WGPUBlendFactor_SrcAlpha());
            WGPUBlendComponent.dstFactor$set(blendColor, WGPUBlendFactor_OneMinusSrcAlpha());
            WGPUBlendComponent.operation$set(blendColor, WGPUBlendOperation_Add());

            var blendAlpha = WGPUBlendState.alpha$slice(blendState);
            WGPUBlendComponent.srcFactor$set(blendAlpha, WGPUBlendFactor_Zero());
            WGPUBlendComponent.dstFactor$set(blendAlpha, WGPUBlendFactor_One());
            WGPUBlendComponent.operation$set(blendAlpha, WGPUBlendOperation_Add());

            var colorTargetState = WGPUColorTargetState.allocate(arena);
            WGPUColorTargetState.format$set(colorTargetState, format);
            WGPUColorTargetState.blend$set(colorTargetState, blendState);
            WGPUColorTargetState.writeMask$set(colorTargetState, WGPUColorWriteMask_All());

            WGPUFragmentState.targetCount$set(fragmentState, 1);
            WGPUFragmentState.targets$set(fragmentState, colorTargetState);

            var multisampleState = WGPURenderPipelineDescriptor.multisample$slice(pipelineDesc);
            WGPUMultisampleState.count$set(multisampleState, 1);
            WGPUMultisampleState.mask$set(multisampleState, 0xFFFFFFFF);
            WGPUMultisampleState.alphaToCoverageEnabled$set(multisampleState, 0);

            WGPURenderPipelineDescriptor.layout$set(pipelineDesc, MemorySegment.NULL);

            var vertices = new float[]{
                    -0.5f, -0.5f,
                    +0.5f, -0.5f,
                    +0.0f, +0.5f,

                    -0.55f, -0.5f,
                    -0.05f, +0.5f,
                    -0.55f, +0.5f
            };
            var vertexCount = vertices.length / 2;

            var bufferDesc = WGPUBufferDescriptor.allocate(arena);
            WGPUBufferDescriptor.size$set(bufferDesc, vertices.length * 4);
            WGPUBufferDescriptor.usage$set(bufferDesc, WGPUBufferUsage_CopyDst() | WGPUBufferUsage_Vertex());
            WGPUBufferDescriptor.mappedAtCreation$set(bufferDesc, 0);
            var buffer = wgpuDeviceCreateBuffer(device.device(), bufferDesc);

            var vertexBufferData = arena.allocateArray(ValueLayout.JAVA_FLOAT, vertices.length);
            vertexBufferData.copyFrom(MemorySegment.ofArray(vertices));

            wgpuQueueWriteBuffer(queue, buffer, 0, vertexBufferData, WGPUBufferDescriptor.size$get(bufferDesc));

            var vertexBufferLayout = WGPUVertexBufferLayout.allocate(arena);

            var vertexAttrib = WGPUVertexAttribute.allocate(arena);
            WGPUVertexAttribute.shaderLocation$set(vertexAttrib, 0);
            WGPUVertexAttribute.format$set(vertexAttrib, WGPUVertexFormat_Float32x2());
            WGPUVertexAttribute.offset$set(vertexAttrib, 0);

            WGPUVertexBufferLayout.attributeCount$set(vertexBufferLayout, 1);
            WGPUVertexBufferLayout.attributes$set(vertexBufferLayout, vertexAttrib);
            WGPUVertexBufferLayout.arrayStride$set(vertexBufferLayout, 2 * 4);
            WGPUVertexBufferLayout.stepMode$set(vertexBufferLayout, WGPUVertexStepMode_Vertex());

            WGPUVertexState.bufferCount$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), 1);
            WGPUVertexState.buffers$set(WGPURenderPipelineDescriptor.vertex$slice(pipelineDesc), vertexBufferLayout);

            var renderPipeline = wgpuDeviceCreateRenderPipeline(device.device(), pipelineDesc);

            SDL_Event evt = new SDL_Event();
            while(true){
                while(SDL_PollEvent(evt) != 0) {
                    switch (evt.type){
                        case SDL_QUIT -> {
                            return;
                        }
                        case SDL_WINDOWEVENT -> {
                            if(evt.window.event == SDL_WINDOWEVENT_RESIZED || evt.window.event == SDL_WINDOWEVENT_SIZE_CHANGED){
                                resize(surface, surfaceConfig, null);
                            }
                        }
                    }
                }

                wgpuSurfaceGetCurrentTexture(surface, nextTex);
                var status = WGPUSurfaceTexture.status$get(nextTex);
                if(status != 0){
                    if(status == WGPUSurfaceGetCurrentTextureStatus_Timeout()
                        || status == WGPUSurfaceGetCurrentTextureStatus_Outdated()
                        || status == WGPUSurfaceGetCurrentTextureStatus_Lost()){

                        resize(surface, surfaceConfig, nextTex);

                    }else{
                        //Thread.sleep(100);
                        continue;
                    }
                }

                var surfaceViewDesc = WGPUTextureViewDescriptor.allocate(arena);
                WGPUTextureViewDescriptor.nextInChain$set(surfaceViewDesc, MemorySegment.NULL);
                WGPUTextureViewDescriptor.format$set(surfaceViewDesc, format);
                WGPUTextureViewDescriptor.dimension$set(surfaceViewDesc, WGPUTextureViewDimension_2D());
                WGPUTextureViewDescriptor.mipLevelCount$set(surfaceViewDesc, 1);
                WGPUTextureViewDescriptor.arrayLayerCount$set(surfaceViewDesc, 1);
                WGPUTextureViewDescriptor.aspect$set(surfaceViewDesc, WGPUTextureAspect_All());

                var nextTexView = wgpuTextureCreateView(
                        WGPUSurfaceTexture.texture$get(nextTex),
                        surfaceViewDesc
                );

                var commandEncoderDesc = WGPUCommandEncoderDescriptor.allocate(arena);
                WGPUCommandEncoderDescriptor.nextInChain$set(commandEncoderDesc, MemorySegment.NULL);
                WGPUCommandEncoderDescriptor.label$set(commandEncoderDesc, arena.allocateUtf8String("Command Encoder"));
                var encoder = wgpuDeviceCreateCommandEncoder(device.device(), commandEncoderDesc);

                var renderPassDesc = WGPURenderPassDescriptor.allocate(arena);
                WGPURenderPassDescriptor.nextInChain$set(renderPassDesc, MemorySegment.NULL);

                var colorAttachment = WGPURenderPassColorAttachment.allocate(arena);
                WGPURenderPassColorAttachment.view$set(colorAttachment, nextTexView);
                WGPURenderPassColorAttachment.resolveTarget$set(colorAttachment, MemorySegment.NULL);
                WGPURenderPassColorAttachment.loadOp$set(colorAttachment, WGPULoadOp_Clear());
                WGPURenderPassColorAttachment.storeOp$set(colorAttachment, WGPUStoreOp_Store());

                var clearValue = WGPURenderPassColorAttachment.clearValue$slice(colorAttachment);
                WGPUColor.r$set(clearValue, 0.9);
                WGPUColor.g$set(clearValue, 0.1);
                WGPUColor.b$set(clearValue, 0.2);
                WGPUColor.a$set(clearValue, 1);

                WGPURenderPassDescriptor.colorAttachmentCount$set(renderPassDesc, 1);
                WGPURenderPassDescriptor.colorAttachments$set(renderPassDesc, colorAttachment);

                WGPURenderPassDescriptor.depthStencilAttachment$set(renderPassDesc, MemorySegment.NULL);
                WGPURenderPassDescriptor.timestampWrites$set(renderPassDesc, MemorySegment.NULL);

                var renderPass = wgpuCommandEncoderBeginRenderPass(encoder, renderPassDesc);

                wgpuRenderPassEncoderSetPipeline(renderPass, renderPipeline);

                wgpuRenderPassEncoderSetVertexBuffer(renderPass, 0, buffer, 0, buffer.byteSize());

                wgpuRenderPassEncoderDraw(renderPass, vertexCount, 1, 0, 0);

                wgpuRenderPassEncoderEnd(renderPass);
                wgpuRenderPassEncoderRelease(renderPass);


                wgpuTextureRelease(nextTex);

                var commandBufferDesc = WGPUCommandBufferDescriptor.allocate(arena);
                WGPUCommandBufferDescriptor.label$set(commandBufferDesc, arena.allocateUtf8String("Command Buffer"));
                WGPUCommandBufferDescriptor.nextInChain$set(commandBufferDesc, MemorySegment.NULL);

                var command = wgpuCommandEncoderFinish(encoder, MemorySegment.NULL);

                var commandArray = arena.allocateArray(WGPUCommandBuffer, 1);
                commandArray.setAtIndex(WGPUCommandBuffer, 0, command);

                wgpuQueueSubmit(queue, 1, commandArray);

                wgpuCommandBufferRelease(command);
                wgpuCommandEncoderRelease(encoder);

                wgpuSurfacePresent(surface);

                wgpuTextureViewRelease(nextTexView);
                wgpuTextureRelease(WGPUSurfaceTexture.texture$get(nextTex));

                Thread.sleep(100);
            }



        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private record AdapterCallbackResult(int status, MemorySegment adapter, String message, MemorySegment userData){}
    private record DeviceCallbackResult(int status, MemorySegment device, String message, MemorySegment userData){}

    private void resize(MemorySegment surface, MemorySegment surfaceConfig, MemorySegment surfaceTexture){
        var width = new IntByReference();
        var height = new IntByReference();
        SDL_GetWindowSize(window, width, height);
        WGPUSurfaceConfiguration.width$set(surfaceConfig, width.getValue());
        WGPUSurfaceConfiguration.height$set(surfaceConfig, height.getValue());

        if(surfaceTexture != null){
            wgpuTextureRelease(WGPUSurfaceTexture.texture$get(surfaceTexture));
        }

        wgpuSurfaceConfigure(surface, surfaceConfig);
    }

    private void run(){
        init();
    }

    public static void main(String[] args){

        int result = SDL_Init(SDL_INIT_EVERYTHING);
        if (result != 0) {
            throw new IllegalStateException("Unable to initialize SDL library (Error code " + result + "): " + SDL_GetError());
        }

        new Main().run();
    }
}
