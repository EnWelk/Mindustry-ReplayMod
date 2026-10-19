package com.enwelk.replaymod;

import java.lang.reflect.Method;

import arc.Core;
import arc.Graphics;
import arc.graphics.Camera;
import arc.input.KeyCode;
import arc.scene.Scene;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.Renderer;
import mindustry.core.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViewerTests {
    private ContentLoader previousContent;
    private Scene previousScene;
    private Graphics previousGraphics;
    private Camera previousCamera;
    private World previousWorld;
    private Renderer previousRenderer;

    private ReplayController controller;
    private Viewer viewer;

    @BeforeEach
    void setUp() {
        previousContent = Vars.content;
        previousScene = Core.scene;
        previousGraphics = Core.graphics;
        previousCamera = Core.camera;
        previousWorld = Vars.world;
        previousRenderer = Vars.renderer;

        ContentLoader content = mock(ContentLoader.class);
        when(content.items()).thenReturn(new Seq<>());
        Vars.content = content;

        controller = mock(ReplayController.class);
        when(controller.isPlaying()).thenReturn(true);
        viewer = new Viewer(controller);

        Core.scene = mock(Scene.class);
        Core.graphics = mock(Graphics.class);
        when(Core.graphics.getWidth()).thenReturn(1000);
        Core.camera = new Camera();
        Core.camera.width = 200f;
        Core.camera.height = 100f;
        Vars.world = mock(World.class);
        when(Vars.world.unitWidth()).thenReturn(1600);
        when(Vars.world.unitHeight()).thenReturn(3200);
        Vars.renderer = mock(Renderer.class);
        when(Vars.renderer.getScale()).thenReturn(1f);
    }

    @AfterEach
    void tearDown() {
        Vars.content = previousContent;
        Core.scene = previousScene;
        Core.graphics = previousGraphics;
        Core.camera = previousCamera;
        Vars.world = previousWorld;
        Vars.renderer = previousRenderer;
    }

    @Test
    void panMovesCameraByWorldScaledDrag() {
        assertFalse(viewer.pan(500f, 300f, 100f, -50f));

        // camera.width / graphics width = 200 / 1000 = 0.2 world units per pixel
        assertEquals(-20f, Core.camera.position.x, 0.001f);
        assertEquals(10f, Core.camera.position.y, 0.001f);
    }

    @Test
    void panIsIgnoredOutsidePlayback() {
        when(controller.isPlaying()).thenReturn(false);

        viewer.pan(500f, 300f, 100f, -50f);

        assertEquals(0f, Core.camera.position.x, 0.001f);
        assertEquals(0f, Core.camera.position.y, 0.001f);
    }

    @Test
    void panIsIgnoredWhileDialogOpen() {
        when(Core.scene.hasDialog()).thenReturn(true);

        viewer.pan(500f, 300f, 100f, -50f);

        assertEquals(0f, Core.camera.position.x, 0.001f);
        assertEquals(0f, Core.camera.position.y, 0.001f);
    }

    @Test
    void panClampsCameraToWorldBounds() {
        when(Vars.world.unitWidth()).thenReturn(100);
        when(Vars.world.unitHeight()).thenReturn(100);

        viewer.pan(500f, 300f, -100_000f, -100_000f);

        assertEquals(100f + Core.camera.width / 4f, Core.camera.position.x, 0.001f);
        assertEquals(100f + Core.camera.height / 4f, Core.camera.position.y, 0.001f);
    }

    @Test
    void zoomScalesRendererFromPinchBaseline() {
        when(Vars.renderer.getScale()).thenReturn(2f);

        assertTrue(viewer.zoom(100f, 150f));

        verify(Vars.renderer).setScale(3f);
    }

    @Test
    void zoomRebasesAfterTouchDown() {
        when(Vars.renderer.getScale()).thenReturn(2f);
        viewer.zoom(100f, 150f);

        viewer.touchDown(10f, 10f, 0, KeyCode.mouseLeft);

        when(Vars.renderer.getScale()).thenReturn(1.5f);
        viewer.zoom(100f, 300f);

        verify(Vars.renderer).setScale(3f);
        verify(Vars.renderer).setScale(4.5f);
    }

    @Test
    void viewerDoesNotOverridePinchStop() {
        for (Method method : Viewer.class.getDeclaredMethods()) {
            assertNotEquals("pinchStop", method.getName(),
                "InputHandler.pinchStop is final in Android builds; overriding it breaks class loading");
        }
    }

    @Test
    void zoomIsIgnoredOutsidePlayback() {
        when(controller.isPlaying()).thenReturn(false);

        assertFalse(viewer.zoom(100f, 150f));

        verify(Vars.renderer, never()).setScale(org.mockito.ArgumentMatchers.anyFloat());
    }
}


