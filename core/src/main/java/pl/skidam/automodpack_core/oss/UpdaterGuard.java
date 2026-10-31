package pl.skidam.automodpack_core.oss;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import org.tomlj.Toml;

/** Updater upgrades are a separate manual operation, including renamed copies of the upstream jar. */
final class UpdaterGuard {
    private static final Set<String> METADATA=Set.of("fabric.mod.json","quilt.mod.json","META-INF/mods.toml","META-INF/neoforge.mods.toml");
    private static final int MAX_METADATA=128*1024;
    private UpdaterGuard() {}
    private static void rejectId(String id) throws IOException {
        if("automodpack".equals(id) || "automodpack_mod".equals(id)) throw new IOException("Updater jars require manual distribution; pack replacement is forbidden");
    }
    static void inspect(String managedPath,Path file) throws IOException {
        if(!managedPath.startsWith("mods/") || !managedPath.toLowerCase(Locale.ROOT).endsWith(".jar")) return;
        SafePaths.requireRegularFile(file);
        ZipFile archive;
        try {archive=new ZipFile(file.toFile());}
        catch(ZipException notArchive) {return; /* Non-jar usability is the signed publisher's concern. */}
        try(var zip=archive) {
            var entries=zip.entries();int count=0,budget=4*MAX_METADATA;
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();if(++count>100000)throw new IOException("Jar entry limit exceeded");
                if(!METADATA.contains(entry.getName()))continue;
                if(entry.getSize()>MAX_METADATA)throw new IOException("Jar mod metadata exceeds limit");
                byte[] bytes;
                try(var input=zip.getInputStream(entry)){bytes=input.readNBytes(MAX_METADATA+1);}
                budget-=bytes.length;if(bytes.length>MAX_METADATA || budget<0)throw new IOException("Jar mod metadata exceeds limit");
                if(entry.getName().endsWith(".json")) {
                    var object=StrictJson.object(bytes);
                    if(entry.getName().equals("quilt.mod.json")) {
                        if(object.has("quilt_loader"))rejectId(StrictJson.string(object.getAsJsonObject("quilt_loader"),"id"));
                    } else rejectId(StrictJson.string(object,"id"));
                } else {
                    var metadata=Toml.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
                    if(metadata.hasErrors())throw new IOException("Invalid jar mod metadata");
                    var mods=metadata.getArray("mods");
                    if(mods!=null)for(int index=0;index<mods.size();index++)rejectId(mods.getTable(index).getString("modId"));
                }
            }
        } catch(RuntimeException malformed) {throw new IOException("Invalid jar mod metadata",malformed);}
    }
}
