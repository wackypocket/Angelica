package com.gtnewhorizons.angelica.glemu.resource;

import com.gtnewhorizons.angelica.glemu.resource.Fbo;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL30;

public final class Framebuffers {
    public interface Host {
        TexBase attachment(int id, boolean renderbuffer);

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
        for (int i = 0; i < Fbo.MAX_COLOR; i++) {
            if (f.colorTex[i] == 0) continue;
            final TexBase t = host.attachment(f.colorTex[i], f.colorRbo[i]);
            if (t == null || !t.hasImage()) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            if (t.format.isDepthOrStencil()) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            any = true;
        }
        final int ds = f.depthTex != 0 ? f.depthTex : f.stencilTex;
        if (ds != 0) {
            final TexBase t = host.attachment(ds, f.depthTex != 0 ? f.depthRbo : f.stencilRbo);
            if (t == null || !t.hasImage() || !t.format.isDepthOrStencil()) return GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            any = true;
        }
        return any ? GL30.GL_FRAMEBUFFER_COMPLETE : GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
    }
}
