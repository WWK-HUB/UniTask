package cn.unitask.attachment;

import cn.unitask.common.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

final class FileTypes {
    record Type(String media, String category, boolean preview) {}

    private static final Set<String> TEXT = Set.of("txt", "md", "csv", "json", "xml", "java", "py", "js", "ts",
            "html", "css", "sql", "c", "cpp", "h", "cs", "sh", "yml", "yaml");
    private static final Map<String, String> OFFICE = Map.of(
            "docx", "word/", "xlsx", "xl/", "pptx", "ppt/",
            "odt", "application/vnd.oasis.opendocument.text",
            "ods", "application/vnd.oasis.opendocument.spreadsheet",
            "odp", "application/vnd.oasis.opendocument.presentation");
    private static final Map<String, String> OOXML = Map.of(
            "docx", "wordprocessingml.document", "xlsx", "spreadsheetml.sheet", "pptx", "presentationml.presentation");

    private FileTypes() {}

    static Type inspect(String name, Path file) throws IOException {
        int dot = name.lastIndexOf('.');
        String ext = dot < 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (TEXT.contains(ext)) return text(file);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) { bytes = in.readNBytes(560); }
        if ((OFFICE.containsKey(ext) || ext.equals("zip"))
                && !signature(bytes,0,80,75,3,4) && !signature(bytes,0,80,75,5,6)) throw unsupported();
        if (OFFICE.containsKey(ext)) return office(ext,file);
        return switch (ext) {
            case "png" -> checked(signature(bytes, 0, 137,80,78,71,13,10,26,10), "image/png", "image", true);
            case "jpg", "jpeg" -> checked(signature(bytes, 0, 255,216,255), "image/jpeg", "image", true);
            case "gif" -> checked(ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a"), "image/gif", "image", true);
            case "webp" -> checked(ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP"), "image/webp", "image", true);
            case "pdf" -> checked(ascii(bytes, 0, "%PDF-"), "application/pdf", "document", true);
            case "doc", "xls", "ppt" -> checked(signature(bytes, 0, 208,207,17,224,161,177,26,225),
                    switch(ext) { case "doc" -> "application/msword"; case "xls" -> "application/vnd.ms-excel";
                        default -> "application/vnd.ms-powerpoint"; }, "document", false);
            case "zip" -> zip(file);
            case "rar" -> checked(signature(bytes, 0, 82,97,114,33,26,7), "application/vnd.rar", "archive", false);
            case "7z" -> checked(signature(bytes, 0, 55,122,188,175,39,28), "application/x-7z-compressed", "archive", false);
            case "tar" -> checked(tar(bytes), "application/x-tar", "archive", false);
            case "gz" -> checked(signature(bytes, 0, 31,139,8), "application/gzip", "archive", false);
            case "mp4" -> checked(ascii(bytes, 4, "ftyp") && !ascii(bytes, 8, "qt  "), "video/mp4", "video", true);
            case "mov" -> checked(ascii(bytes, 4, "ftyp") || ascii(bytes, 4, "moov") || ascii(bytes, 4, "mdat"),
                    "video/quicktime", "video", false);
            case "webm" -> checked(ebml(bytes, "webm"), "video/webm", "video", true);
            case "mkv" -> checked(ebml(bytes, "matroska"), "video/x-matroska", "video", false);
            case "avi" -> checked(ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "AVI "), "video/x-msvideo", "video", false);
            case "mp3" -> checked(ascii(bytes, 0, "ID3") || (bytes.length >= 2 && (bytes[0] & 255) == 255
                    && (bytes[1] & 230) == 226), "audio/mpeg", "audio", true);
            case "wav" -> checked(ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE"), "audio/wav", "audio", true);
            case "m4a" -> checked(ascii(bytes, 4, "ftyp"), "audio/mp4", "audio", true);
            case "ogg" -> checked(ascii(bytes, 0, "OggS"), "audio/ogg", "audio", true);
            default -> throw unsupported();
        };
    }

    private static boolean tar(byte[] bytes) {
        if (bytes.length < 512) return false;
        String octal = new String(bytes, 148, 8, StandardCharsets.US_ASCII).trim();
        if (!octal.matches("[0-7]{1,8}")) return false;
        int sum = 0;
        for (int i = 0; i < 512; i++) sum += i >= 148 && i < 156 ? 32 : bytes[i] & 255;
        return sum == Integer.parseInt(octal, 8);
    }

    private static boolean ebml(byte[] bytes, String type) {
        return signature(bytes, 0, 26,69,223,163)
                && new String(bytes, StandardCharsets.ISO_8859_1).contains(type);
    }

    private static Type text(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] sample = in.readNBytes(8192);
            if (ascii(sample, 0, "MZ") && sample.length > 64 && sample[2] == (byte) 0x90) throw unsupported();
            if (signature(sample, 0, 255,254) || signature(sample, 0, 254,255))
                return new Type("text/plain", "text", false);
            for (byte b : sample) {
                if ((b & 255) < 32 && b != 9 && b != 10 && b != 13 && b != 12) throw unsupported();
            }
        }
        return new Type("text/plain", "text", false);
    }

    private static Type zip(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            zip.size();
            return new Type("application/zip", "archive", false);
        } catch (IOException ex) { throw unsupported(); }
    }

    private static Type office(String ext, Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String marker = OFFICE.get(ext);
            if (OOXML.containsKey(ext)) {
                boolean structure = zip.getEntry("[Content_Types].xml") != null &&
                        zip.stream().anyMatch(entry -> entry.getName().startsWith(marker) && !entry.isDirectory());
                return checked(structure, "application/vnd.openxmlformats-officedocument." + OOXML.get(ext),
                        "document", false);
            }
            var entry = zip.getEntry("mimetype");
            if (entry == null || entry.getSize() > 96 || zip.getEntry("content.xml") == null) throw unsupported();
            try (InputStream in = zip.getInputStream(entry)) {
                return checked(marker.equals(new String(in.readNBytes(97), StandardCharsets.US_ASCII)),
                        marker, "document", false);
            }
        } catch (IOException ex) { throw unsupported(); }
    }

    static Type stored(String mime) {
        String category = mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video"
                : mime.startsWith("audio/") ? "audio" : mime.startsWith("text/") ? "text"
                : mime.equals("application/pdf") || mime.contains("document") || mime.contains("word")
                    || mime.contains("excel") || mime.contains("powerpoint") || mime.contains("spreadsheet")
                    || mime.contains("presentation") ? "document" : "archive";
        boolean preview = Set.of("image/jpeg","image/png","image/webp","image/gif","application/pdf",
                "video/mp4","video/webm","audio/wav","audio/ogg","audio/mpeg","audio/mp4").contains(mime);
        return new Type(mime, category, preview);
    }

    private static boolean ascii(byte[] data, int start, String value) {
        if (data.length < start + value.length()) return false;
        for (int i = 0; i < value.length(); i++) if (data[start + i] != value.charAt(i)) return false;
        return true;
    }

    private static boolean signature(byte[] data, int start, int... bytes) {
        if (data.length < start + bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if ((data[start+i] & 255) != bytes[i]) return false;
        return true;
    }

    private static Type checked(boolean valid, String media, String category, boolean preview) {
        if (!valid) throw unsupported();
        return new Type(media, category, preview);
    }

    private static Problem unsupported() {
        return new Problem(415, "UNSUPPORTED_FILE", "文件格式不受支持或与扩展名不符");
    }
}
