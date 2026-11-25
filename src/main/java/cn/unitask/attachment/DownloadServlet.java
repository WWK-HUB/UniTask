package cn.unitask.attachment;

import cn.unitask.common.Problem;
import cn.unitask.security.AccountPrincipal;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.springframework.web.util.UriUtils;

public final class DownloadServlet extends HttpServlet {
    private static final Pattern RESOURCE = Pattern.compile("/([1-9][0-9]{0,18})/(download|preview)");
    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]*)-([0-9]*)");
    private final AttachmentService files;

    public DownloadServlet(AttachmentService files) { this.files = files; }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        serve(request,response,false);
    }

    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        serve(request,response,true);
    }

    private void serve(HttpServletRequest request, HttpServletResponse response, boolean headersOnly) throws IOException {
        Long actor = AccountPrincipal.currentId();
        if (actor == null) { response.sendError(401); return; }
        String requested = request.getPathInfo();
        var match = RESOURCE.matcher(requested == null ? "" : requested);
        if (!match.matches()) { response.sendError(404); return; }
        AttachmentService.Download file;
        try { file = files.download(actor,Long.parseLong(match.group(1))); }
        catch (Problem problem) { response.sendError(problem.status()); return; }
        catch (NumberFormatException ex) { response.sendError(404); return; }

        boolean inline = "preview".equals(match.group(2)) && file.preview();
        boolean rangeSupported = inline && ("video".equals(file.category()) || "audio".equals(file.category()));
        Segment segment = new Segment(0,file.size()-1);
        if (rangeSupported) {
            response.setHeader("Accept-Ranges","bytes");
            String header = request.getHeader("Range");
            if (header != null) {
                segment = parseRange(header,file.size());
                if (segment == null) {
                    response.setHeader("Content-Range","bytes */" + file.size());
                    response.sendError(416);
                    return;
                }
                response.setStatus(206);
                response.setHeader("Content-Range","bytes " + segment.start() + "-" + segment.end() + "/" + file.size());
            }
        }
        response.setHeader("Cache-Control","private, no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        if (inline && "application/pdf".equals(file.mime())) {
            response.setHeader("Content-Security-Policy","sandbox; default-src 'none'; frame-ancestors 'self'");
        }
        response.setContentType(file.mime());
        String fallback = file.name().replaceAll("[^a-zA-Z0-9 ._-]","_").strip();
        if (!fallback.matches(".*[a-zA-Z0-9].*")) fallback = "attachment";
        response.setHeader("Content-Disposition",(inline ? "inline" : "attachment")
                + "; filename=\"" + fallback + "\"; filename*=UTF-8''"
                + UriUtils.encode(file.name(),StandardCharsets.UTF_8));
        response.setContentLengthLong(segment.length());
        if (headersOnly) return;

        try (FileChannel channel = FileChannel.open(file.path(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            channel.position(segment.start());
            var output = response.getOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long remaining = segment.length();
            while (remaining > 0) {
                buffer.clear().limit((int)Math.min(buffer.capacity(),remaining));
                int read = channel.read(buffer);
                if (read < 0) throw new IOException("Unexpected end of attachment");
                output.write(buffer.array(),0,read);
                remaining -= read;
            }
        }
    }

    record Segment(long start, long end) { long length() { return end - start + 1; } }

    static Segment parseRange(String header, long size) {
        var matcher = RANGE.matcher(header.strip());
        if (!matcher.matches() || size <= 0) return null;
        try {
            String left = matcher.group(1);
            String right = matcher.group(2);
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                return suffix <= 0 ? null : new Segment(Math.max(0,size-suffix),size-1);
            }
            long start = Long.parseLong(left);
            long end = right.isEmpty() ? size-1 : Math.min(Long.parseLong(right),size-1);
            return start >= size || end < start ? null : new Segment(start,end);
        } catch (NumberFormatException invalid) { return null; }
    }
}
