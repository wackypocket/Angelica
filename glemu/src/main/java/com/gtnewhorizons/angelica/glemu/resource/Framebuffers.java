package com.gtnewhorizons.angelica.glemu.resource;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

public final class Framebuffers {
    public interface Host {
        TexBase attachment(int id, boolean renderbuffer);

        boolean renderable(int width, int height);

        void targetsChanged();
    }

    private final Host host;
    private final Int2ObjectOpenHashMap<Fbo> fbos = new Int2ObjectOpenHashMap<>();
    public final Fbo defaultFbo = new Fbo(0);
    private int nextId = 1;

    public Framebuffers(Host host) {
        this.host = host;
    }

    public int gen() {
        int id = nextId++;
        while (fbos.containsKey(id)) id = nextId++;
        fbos.put(id, new Fbo(id));
        return id;
    }

    public Fbo get(int id) {
        return id == 0 ? defaultFbo : fbos.get(id);
    }

    public Fbo getOrCreate(int id) {
        if (id == 0) return defaultFbo;
        Fbo f = fbos.get(id);
        if (f == null) {
            f = new Fbo(id);
            fbos.put(id, f);
            if (id >= nextId) nextId = id + 1;
        }
        return f;
    }

    public boolean exists(int id) {
        return id != 0 && fbos.containsKey(id);
    }

    public void delete(int id) {
        if (fbos.remove(id) != null) host.targetsChanged();
    }

    public void attach(Fbo f, int attachment, int texId, boolean rbo, int level, int layer, boolean layered) {
        if (f == null || f.id == 0) return;
        final int idx = attachment - GL30.GL_COLOR_ATTACHMENT0;
        if (idx >= 0 && idx < Fbo.MAX_COLOR) {
            f.colorTex[idx] = texId;
            f.colorRbo[idx] = rbo;
            f.colorLevel[idx] = level;
            f.colorLayer[idx] = layer;
            f.colorLayered[idx] = layered;
        } else if (attachment == GL30.GL_DEPTH_ATTACHMENT) {
            f.depthTex = texId;
            f.depthRbo = rbo;
            f.depthLevel = level;
            f.depthLayer = layer;
            f.depthLayered = layered;
        } else if (attachment == GL30.GL_STENCIL_ATTACHMENT) {
            f.stencilTex = texId;
            f.stencilRbo = rbo;
            f.stencilLevel = level;
            f.stencilLayer = layer;
        } else if (attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT) {
            f.depthTex = f.stencilTex = texId;
            f.depthRbo = f.stencilRbo = rbo;
            f.depthLevel = f.stencilLevel = level;
            f.depthLayer = f.stencilLayer = layer;
            f.depthLayered = layered;
        } else {
            return;
        }
        f.gen++;
        host.targetsChanged();
    }

    public void onTextureDeleted(int id, boolean rbo) {
        for (Fbo f : fbos.values()) {
            boolean changed = false;
            for (int i = 0; i < Fbo.MAX_COLOR; i++) {
                if (f.colorTex[i] == id && f.colorRbo[i] == rbo) {
                    f.colorTex[i] = 0;
                    changed = true;
                }
            }
            if (f.depthTex == id && f.depthRbo == rbo) {
                f.depthTex = 0;
                changed = true;
            }
            if (f.stencilTex == id && f.stencilRbo == rbo) {
                f.stencilTex = 0;
                changed = true;
            }
            if (changed) f.gen++;
        }
        host.targetsChanged();
    }

    public int status(Fbo f) {
        if (f == null) return GL30.GL_FRAMEBUFFER_UNDEFINED;
        if (f.id == 0) return GL30.GL_FRAMEBUFFER_COMPLETE;
        boolean any = false;
        int samples = 0;
        if (layersDisagree(f)) return GL32.GL_FRAMEBUFFER_INCOMPLETE_LAYER_TARGETS;
        for (int i = 0; i < Fbo.MAX_COLOR; i++) {
            if (f.colorTex[i] == 0) continue;
            final TexBase t = host.attachment(f.colorTex[i], f.colorRbo[i]);
            if (t == null || !t.hasImage()) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            if (samples != 0 && t.samples != samples) return GL30.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE;
            samples = t.samples;
            if (t.format.isDepthOrStencil() || t.format.isCompressed() || t.format.pf() == PixelFormats.PF_RGB9E5_FLOAT) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            if (!f.colorRbo[i] && !levelAttachable(t, f.colorLevel[i])) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            if (!host.renderable(t.levelWidth(f.colorLevel[i]), t.levelHeight(f.colorLevel[i]))) return GL30.GL_FRAMEBUFFER_UNSUPPORTED;
            any = true;
        }
        final int ds = f.depthTex != 0 ? f.depthTex : f.stencilTex;
        if (ds != 0) {
            final TexBase t = host.attachment(ds, f.depthTex != 0 ? f.depthRbo : f.stencilRbo);
            if (t == null || !t.hasImage() || !t.format.isDepthOrStencil()) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            if (samples != 0 && t.samples != samples) return GL30.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE;
            final int level = f.depthTex != 0 ? f.depthLevel : f.stencilLevel;
            if (!(f.depthTex != 0 ? f.depthRbo : f.stencilRbo) && !levelAttachable(t, level)) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            if (!host.renderable(t.levelWidth(level), t.levelHeight(level))) return GL30.GL_FRAMEBUFFER_UNSUPPORTED;
            any = true;
        }
        return any || f.defaultWidth > 0 && f.defaultHeight > 0 ? GL30.GL_FRAMEBUFFER_COMPLETE : GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
    }

    /**
     * A texture level can be attached from the base level up. Past the base level a mutable texture has to be mipmap complete,
     * which an immutable one always is.
     */
    private static boolean levelAttachable(TexBase t, int level) {
        if (level < t.baseLevel) return false;
        if (level == t.baseLevel || t.immutable) return true;
        return t.completeFor(org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR);
    }

    /** Layered attachments have to be all of them, and the colour ones all of one kind of texture. */
    private boolean layersDisagree(Fbo f) {
        int layered = 0, single = 0, target = 0;
        for (int i = 0; i < Fbo.MAX_COLOR; i++) {
            if (f.colorTex[i] == 0) continue;
            if (!f.colorLayered[i]) {
                single++;
                continue;
            }
            layered++;
            final TexBase t = host.attachment(f.colorTex[i], f.colorRbo[i]);
            if (t == null) continue;
            if (target != 0 && t.target != target) return true;
            target = t.target;
        }
        if (f.depthTex != 0 || f.stencilTex != 0) {
            if (f.depthLayered) layered++;
            else single++;
        }
        return layered > 0 && single > 0;
    }
}
