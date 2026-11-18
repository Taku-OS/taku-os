package org.agentos.lab;

import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;

/** Copies declared package images into a private, content-addressed UI cache. No URL fetching. */
final class SkillIcons {
    private static final int LIMIT = 2097152;
    private SkillIcons() {}

    private static boolean raster(String path) {
        return path.matches("(?i).+\\.(png|jpe?g|webp)");
    }

    private static JSONObject interfaceYaml(String text) throws Exception {
        JSONObject result = new JSONObject();
        boolean inside = false;
        for (String line : text.split("\\r?\\n")) {
            if (line.equals("interface:")) { inside = true; continue; }
            if (!inside) continue;
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) break;
            String field = line.trim();
            for (String key : new String[]{"icon_small", "icon_large"}) {
                if (!field.startsWith(key + ":")) continue;
                String value = field.substring(key.length() + 1).trim();
                if (value.length() > 1 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                result.put(key.equals("icon_small") ? "iconSmall" : "iconLarge", value);
            }
        }
        return result;
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            if (output.size() > LIMIT) throw new IOException("Skill icon size limit");
        }
        return output.toByteArray();
    }

    private static void cache(JSONObject target, byte[] bytes) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096)
            throw new IOException("Unsupported skill icon dimensions");
        String digest = HarnessStore.hash(bytes);
        File folder = new File(HarnessContext.store().root, "skill-icons"); folder.mkdirs();
        File file = new File(folder, digest + ".image");
        if (!file.exists()) {
            File temporary = new File(folder, digest + ".partial");
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
            if (!temporary.renameTo(file)) throw new IOException("Skill icon cache activation failed");
            android.system.Os.chmod(file.getPath(), 0644);
        }
        target.put("iconPath", file.getPath()).put("iconSha256", digest).put("iconOrigin", "skill-package");
    }

    static void installed(JSONObject target, File skillRoot, JSONObject nativeInterface) {
        try {
            JSONObject metadata = nativeInterface;
            File yaml = new File(skillRoot, "agents/openai.yaml");
            if (metadata == null && yaml.isFile() && yaml.length() <= 131072) metadata = interfaceYaml(TaskBroker.read(yaml));
            if (metadata == null) return;
            for (String key : new String[]{"iconSmall", "iconLarge"}) {
                String declared = metadata.optString(key);
                if (!raster(declared)) continue;
                File file = new File(declared);
                if (!file.isAbsolute()) file = new File(skillRoot, declared);
                if (!file.getCanonicalPath().startsWith(skillRoot.getCanonicalPath() + "/") || !file.isFile() || file.length() > LIMIT) continue;
                cache(target, Files.readAllBytes(file.toPath())); return;
            }
        } catch (Exception unavailable) { /* Missing or unsupported images retain the category fallback. */ }
    }

    static void archived(JSONObject target, ZipFile archive, String skillRoot) {
        try {
            ZipEntry yaml = archive.getEntry(skillRoot + "agents/openai.yaml");
            if (yaml == null || yaml.getSize() > 131072) return;
            JSONObject metadata;
            try (InputStream input = archive.getInputStream(yaml)) {
                metadata = interfaceYaml(new String(read(input), java.nio.charset.StandardCharsets.UTF_8));
            }
            for (String key : new String[]{"iconSmall", "iconLarge"}) {
                String path = metadata.optString(key);
                if (path.startsWith("./")) path = path.substring(2);
                if (!raster(path) || !path.startsWith("assets/") || path.contains("\\")
                        || java.util.Arrays.asList(path.split("/")).contains("..")) continue;
                ZipEntry image = archive.getEntry(skillRoot + path);
                if (image == null || image.getSize() > LIMIT) continue;
                try (InputStream input = archive.getInputStream(image)) { cache(target, read(input)); return; }
            }
        } catch (Exception unavailable) { /* Image support does not determine whether a skill can be used. */ }
    }
}
