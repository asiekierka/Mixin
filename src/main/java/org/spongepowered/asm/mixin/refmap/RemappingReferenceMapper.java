/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.mixin.refmap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.mixin.MixinEnvironment;

import org.spongepowered.asm.mixin.extensibility.IRemapper;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.util.ObfuscationUtil;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * This adapter is designed to apply the same remapping used elsewhere in
 * the development chain (RemapperChain) to reference maps.
 */
public final class RemappingReferenceMapper implements IReferenceMapper {
    /**
     * Logger
     */
    private static final Logger logger = LogManager.getLogger("mixin");
    
    /**
     * The "inner" refmap, this is the original refmap specified in the config
     */
    private final IReferenceMapper refMap;
    
    /**
     * The remapper in use.
     */
    private final IRemapper remapper;

    /**
     * A map between reference mapper values and their remapped equivalents.
     */
    private final Map<String, String> mappedReferenceCache = new HashMap<String, String>();

    private RemappingReferenceMapper(MixinEnvironment env, IReferenceMapper refMap) {
        this.refMap = refMap;
        this.remapper = env.getRemappers();

        RemappingReferenceMapper.logger.info("Remapping refMap {} using remapper chain", refMap.getResourceName());
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.refmap.IReferenceMapper#isDefault()
     */
    @Override
    public boolean isDefault() {
        return this.refMap.isDefault();
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.refmap.IReferenceMapper
     *      #getResourceName()
     */
    @Override
    public String getResourceName() {
        return this.refMap.getResourceName();
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.refmap.IReferenceMapper#getStatus()
     */
    @Override
    public String getStatus() {
        return this.refMap.getStatus();
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.refmap.IReferenceMapper#getContext()
     */
    @Override
    public String getContext() {
        return this.refMap.getContext();
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.refmap.IReferenceMapper#setContext(
     *      java.lang.String)
     */
    @Override
    public void setContext(String context) {
        this.refMap.setContext(context);
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.refmap.IReferenceMapper#remap(
     *      java.lang.String, java.lang.String)
     */
    @Override
    public String remap(String className, String reference) {
        return this.remapWithContext(getContext(), className, reference);
    }

    private static String remapMethodDescriptor(IRemapper remapper, String desc) {
        StringBuilder newDesc = new StringBuilder();
        newDesc.append('(');
        for (Type arg : Type.getArgumentTypes(desc)) {
            newDesc.append(remapper.mapDesc(arg.getDescriptor()));
        }
        return newDesc.append(')').append(remapper.mapDesc(Type.getReturnType(desc).getDescriptor())).toString();
    }

    private String findFieldOwner(MemberInfo obfInfo) {
        ClassInfo c = ClassInfo.forName(remapper.map(obfInfo.owner));

        while (c != null) {
            String owner = remapper.unmap(c.getName());
            if (!remapper.mapFieldName(owner, obfInfo.name, obfInfo.desc).equals(obfInfo.name)) {
                return owner;
            }

            if (c.getSuperName().startsWith("java/")) {
                break;
            }

            c = c.getSuperClass();
        }

        return obfInfo.owner;
    }

    // Yes, I know the code below is very slow. A proper solution would
    // involve either providing a new IRemapper contract (with has() or
    // Optionals; or dependency resolution on the remapper's side), or
    // adding a new interface for reference remappers.
    //
    // Will do for now, though. We'll see after Mixin 0.8 is out. ...Right?

    private String findMethodOwner(MemberInfo obfInfo) {
        LinkedList<ClassInfo> classInfos = new LinkedList<ClassInfo>();
        classInfos.add(ClassInfo.forName(remapper.map(obfInfo.owner)));

        while (!classInfos.isEmpty()) {
            ClassInfo c = classInfos.remove();
            String owner = remapper.unmap(c.getName());
            if (!remapper.mapMethodName(owner, obfInfo.name, obfInfo.desc).equals(obfInfo.name)) {
                return owner;
            }

            if (!c.getSuperName().startsWith("java/")) {
                ClassInfo cSuper = c.getSuperClass();
                if (cSuper != null) {
                    classInfos.add(cSuper);
                }
            }

            for (String s : c.getInterfaces()) {
                if (s.startsWith("java/")) {
                    continue;
                }

                ClassInfo cItf = ClassInfo.forName(s);
                if (cItf != null) {
                    classInfos.add(cItf);
                }
            }
        }

        return obfInfo.owner;
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.refmap.IReferenceMapper
     *      #remapWithContext(java.lang.String, java.lang.String,
     *      java.lang.String)
     */
    @Override
    public String remapWithContext(String context, String className, String reference) {
        // TODO: add caching
        String origInfoString = this.refMap.remapWithContext(context, className, reference);
        String remappedCached = mappedReferenceCache.get(origInfoString);
        if (remappedCached != null) {
            return remappedCached;
        } else {
            String remapped = origInfoString;
            if (remapped.charAt(0) == 'L') {
                // To handle propagation, find super/itf-class (for IRemapper)
                // but pass the requested class in the MemberInfo
                MemberInfo info = MemberInfo.parse(remapped);
                if (info.isField()) {
                    String obfOwner = findFieldOwner(info);
                    remapped = new MemberInfo(
                            remapper.mapFieldName(obfOwner, info.name, info.desc),
                            remapper.map(info.owner),
                            remapper.mapDesc(info.desc)
                    ).toString();
                } else {
                    String obfOwner = findMethodOwner(info);
                    remapped = new MemberInfo(
                            remapper.mapMethodName(obfOwner, info.name, info.desc),
                            remapper.map(info.owner),
                            remapMethodDescriptor(remapper, info.desc)
                    ).toString();
                }
            } else {
                remapped = remapper.map(remapped);
            }
            mappedReferenceCache.put(origInfoString, remapped);
            return remapped;
        }
    }
    
    /**
     * Wrap the specified refmap in a remapping adapter using settings in the
     * supplied environment
     * 
     * @param env environment to read configuration from
     * @param refMap refmap to wrap
     * @return wrapped refmap or original refmap is srg data is not available
     */
    public static IReferenceMapper of(MixinEnvironment env, IReferenceMapper refMap) {
        if (!refMap.isDefault()) {
            return new RemappingReferenceMapper(env, refMap);
        }
        return refMap;
    }

}
